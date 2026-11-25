package cn.edu.cczu.course.module.academic.service;

import cn.edu.cczu.course.common.exception.BusinessException;
import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import cn.edu.cczu.course.module.audit.service.AuditService;
import cn.edu.cczu.course.module.learning.mapper.LearningMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.*;

/** 处理学生选课、退课和课表查询，负责检查名额、学分和上课时间。 */
@Service
@Transactional(readOnly = true)
public class EnrollmentService {
    private final AcademicMapper mapper;
    private final AcademicSupport support;
    private static final List<String> WEEKDAYS = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");
    private final LearningMapper learning;
    private final AuditService audit;

    public EnrollmentService(AcademicMapper mapper, AcademicSupport support, LearningMapper learning, AuditService audit) {
        this.mapper = mapper;
        this.support = support;
        this.learning = learning;
        this.audit = audit;
    }

    // 读取已发布教学班，并补上课表、选课窗口等展示信息。
    public List<ClassView> catalog(Long semesterId) { return support.classViews(mapper.publishedClasses(semesterId)); }
    // 返回全部学期，供课程目录和课表页面选择。
    public List<Semester> semesters() { return mapper.allSemesters(); }
    // 按当前学生读取选退课历史，为每条记录附上对应的排课。
    public List<EnrollmentView> mine(String username, Long semesterId) {
        var student = require(mapper.studentByUsername(username), "当前账号缺少学生档案，请联系管理员");
        var rows = mapper.studentEnrollments(student.id(), semesterId);
        var schedules = support.schedulesByClass(rows.stream().map(Enrollment::teachingClassId).toList());
        return rows.stream().map(row -> new EnrollmentView(row, schedules.getOrDefault(row.teachingClassId(), List.of()))).toList();
    }

    // 提交一个教学班，与批量选课共用同一套验证。
    @Transactional
    public EnrollmentView enroll(String username, Long classId) {
        return enrollSelections(username, List.of(classId)).getFirst();
    }

    // 检查提交个数与重复编号，再把整组课程交给同一事务处理。
    @Transactional
    public List<EnrollmentView> enrollBatch(String username, List<Long> classIds) {
        valid(classIds != null && !classIds.isEmpty() && classIds.size() <= 20, "每次请选择 1～20 个教学班");
        valid(new HashSet<>(classIds).size() == classIds.size(), "不能重复提交同一教学班");
        return enrollSelections(username, classIds);
    }

    // 先锁定相关记录，再逐门校验和写入。遇到失败时整组回滚。
    private List<EnrollmentView> enrollSelections(String username, List<Long> classIds) {
        var student = require(mapper.studentByUsername(username), "当前账号缺少学生档案");
        // 先查出本次选择涉及哪些学期，确定随后要锁定的范围。锁住后会重新查询，避免使用过期的容量或安排。
        var initialById = mapper.selectionClasses(classIds).stream()
                .collect(Collectors.toMap(SelectionClass::id, Function.identity()));
        var initialClasses = classIds.stream().map(id -> require(initialById.get(id), "教学班不存在")).toList();
        var semesterIds = initialClasses.stream().map(SelectionClass::semesterId).distinct().toList();
        // 与单选和退课采用同一锁顺序，同类锁按编号获取，避免不同提交顺序互相等待。
        support.lockSemesters(semesterIds);
        require(mapper.lockStudent(student.id()), "学生档案不存在");
        classIds.stream().sorted().forEach(id -> require(mapper.lockClass(id), "教学班不存在"));
        check(require(mapper.student(student.id()), "学生不存在").enabled(), "学生账号已停用");

        // 拿到锁后重新查一次，以数据库此刻的教学班、名额和学生选课记录为准。
        var selectedAt = support.now();
        var currentClasses = mapper.selectionClasses(classIds).stream()
                .collect(Collectors.toMap(SelectionClass::id, Function.identity()));
        var semesters = mapper.allSemesters().stream().collect(Collectors.toMap(Semester::id, Function.identity()));
        var active = mapper.activeSelections(student.id(), semesterIds);
        // 按学期保存有效选课。每门课累计学分和比较时间时只需要参考自己的学期。
        var activeBySemester = active.stream().collect(Collectors.groupingBy(ActiveSelection::semesterId,
                Collectors.toCollection(ArrayList::new)));
        var schedules = support.schedulesByClass(Stream.concat(classIds.stream(),
                active.stream().map(ActiveSelection::teachingClassId)).toList());
        // 提前批量取出当前名额、成绩和历史报名状态，后续循环按班号查找，减少逐个查询数据库。
        var selectedCounts = mapper.selectedCounts(classIds).stream()
                .collect(Collectors.toMap(ClassCount::teachingClassId, ClassCount::total));
        var publishedCounts = learning.publishedCounts(classIds).stream()
                .collect(Collectors.toMap(ClassCount::teachingClassId, ClassCount::total));
        var existingIds = mapper.existingEnrollments(student.id(), classIds).stream()
                .collect(Collectors.toMap(ClassEnrollmentId::teachingClassId, ClassEnrollmentId::enrollmentId));

        var enrolledInBatch = new HashSet<Long>();
        for (var initial : initialClasses) {
            var current = require(currentClasses.get(initial.id()), "教学班不存在");
            check(current.semesterId().equals(initial.semesterId()), "开课信息已变更，请刷新后重试");
            var semesterActive = activeBySemester.computeIfAbsent(current.semesterId(), ignored -> new ArrayList<>());
            validateSelection(current, semesters.get(current.semesterId()), semesterActive, schedules,
                    selectedCounts.getOrDefault(current.id(), 0L), publishedCounts.getOrDefault(current.id(), 0L), enrolledInBatch);
            // 曾经退过这门教学班时，恢复原来的选课记录；首次选择时才新增记录。
            var existingId = existingIds.get(current.id());
            if (existingId == null) mapper.insertEnrollment(student.id(), current.id(), selectedAt);
            else mapper.reactivateEnrollment(existingId, selectedAt);
            // 已通过的课程参与本批后续校验，任一失败时整批回滚。
            semesterActive.add(ActiveSelection.from(current));
            enrolledInBatch.add(current.id());
        }
        // 从数据库读回最终状态，按提交顺序组织响应，方便前端对应到每个勾选项。
        var saved = mapper.selectedEnrollments(student.id(), classIds).stream()
                .collect(Collectors.toMap(Enrollment::teachingClassId, Function.identity()));
        return classIds.stream().map(id -> new EnrollmentView(require(saved.get(id), "选课记录不存在"),
                schedules.getOrDefault(id, List.of()))).toList();
    }

    // 检查开课状态、可选时间、容量、学分以及与已选课的时间冲突。
    private void validateSelection(SelectionClass teachingClass, Semester semester, List<ActiveSelection> active,
                                   Map<Long, List<Schedule>> schedules, long selectedCount, long publishedCount,
                                   Set<Long> enrolledInBatch) {
        var classId = teachingClass.id();
        String target = "“" + teachingClass.courseName() + "”（" + teachingClass.classCode() + "）";
        check(teachingClass.status().equals("PUBLISHED"), target + "尚未发布或已经关闭选课");
        check(publishedCount == 0, target + "已发布成绩，不能新增选课");
        check(teachingClass.teacherEnabled(), target + "的教师账号已停用");
        support.requireSelectionOpen(require(semester, "学期不存在"));
        check(active.stream().noneMatch(row -> row.courseId().equals(teachingClass.courseId())), target + "在本学期不能重复选择教学班");
        check(selectedCount < teachingClass.capacity(), target + "名额已满");
        // 把本学期已选的学分和当前课程的学分加起来，其中包括本批前面通过校验的课程。
        BigDecimal credits = active.stream().map(ActiveSelection::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
        check(credits.add(teachingClass.credits()).compareTo(semester.maxCredits()) <= 0, "选择" + target + "后将超过本学期学分上限");
        var targetSchedules = schedules.getOrDefault(classId, List.of());
        check(!targetSchedules.isEmpty(), target + "尚未安排上课时间，请联系管理员");
        // 一门课可能有多段排课，因此将目标时段和每门已选课的时段逐对比较。
        for (var selected : active) {
            for (var currentSlot : schedules.getOrDefault(selected.teachingClassId(), List.of())) {
                for (var targetSlot : targetSchedules) {
                    if (overlaps(currentSlot.weekday(), currentSlot.startPeriod(), currentSlot.endPeriod(), currentSlot.startWeek(), currentSlot.endWeek(),
                            targetSlot.weekday(), targetSlot.startPeriod(), targetSlot.endPeriod(), targetSlot.startWeek(), targetSlot.endWeek())) {
                        String source = enrolledInBatch.contains(selected.teachingClassId()) ? "本次待选课程" : "已选课程";
                        throw new BusinessException(HttpStatus.CONFLICT, target + "与" + source + "“" + selected.courseName()
                                + "”（" + selected.classCode() + "）时间冲突：" + overlapText(currentSlot, targetSlot));
                    }
                }
            }
        }
    }

    // 把两门课实际重叠的节次和周次整理成冲突提示。
    private static String overlapText(Schedule first, Schedule second) {
        // 取两段安排共同覆盖的节次和周次，让提示指出实际冲突的那一段。
        int firstPeriod = Math.max(first.startPeriod(), second.startPeriod());
        int lastPeriod = Math.min(first.endPeriod(), second.endPeriod());
        int firstWeek = Math.max(first.startWeek(), second.startWeek());
        int lastWeek = Math.min(first.endWeek(), second.endWeek());
        return WEEKDAYS.get(first.weekday() - 1) + " 第" + range(firstPeriod, lastPeriod) + "节，第" + range(firstWeek, lastWeek) + "周";
    }

    // 单点只显示一个数字，区间显示起止两个数字。
    private static String range(int first, int last) {
        return first == last ? Integer.toString(first) : first + "～" + last;
    }

    // 验证记录归属和退课条件，更新状态并保留退课历史。
    @Transactional
    public void drop(String username, Long enrollmentId) {
        var student = require(mapper.studentByUsername(username), "当前账号缺少学生档案");
        var initial = require(mapper.enrollment(enrollmentId), "选课记录不存在");
        // 先验证记录属于当前学生，再锁需要检查的学期和教学班，防止操作其他人的选课记录。
        if (!initial.studentId().equals(student.id())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只能退选本人课程");
        }
        support.lockSemesters(List.of(initial.semesterId()));
        require(mapper.lockStudent(student.id()), "学生不存在");
        require(mapper.lockClass(initial.teachingClassId()), "教学班不存在");
        // 拿到锁后再确认是否已退选或录入了成绩，避免两个操作同时修改同一条记录。
        var enrollment = require(mapper.enrollment(enrollmentId), "选课记录不存在");
        check(enrollment.status().equals("ENROLLED"), "该课程已经退选，请刷新列表");
        check(learning.recordedCount(enrollmentId)==0, "该课程已录入成绩，不能退课，请联系任课教师");
        support.requireSelectionOpen(require(mapper.semester(enrollment.semesterId()), "学期不存在"));
        mapper.dropEnrollment(enrollmentId, support.now());
        audit.record(username, "ENROLL_DROP", "ENROLLMENT", enrollmentId, "退选教学班 " + enrollment.classCode());
    }

    // 把有效选课的上课安排展开成课表条目，可筛选指定周次。
    public List<TimetableItem> timetable(String username, Long semesterId, Integer week) {
        valid(week == null || week >= 1 && week <= 30, "周次必须为 1～30");
        // 同一课程的多个上课时段拆成独立条目。指定周次时只保留该周实际发生的安排。
        return mine(username, semesterId).stream().filter(view -> view.info().status().equals("ENROLLED"))
                .flatMap(view -> view.schedules().stream()
                        .filter(slot -> week == null || week >= slot.startWeek() && week <= slot.endWeek())
                        .map(slot -> new TimetableItem(view.info().teachingClassId(), view.info().courseName(), view.info().classCode(),
                                view.info().teacherName(), view.info().semesterName(), slot.weekday(), slot.startPeriod(), slot.endPeriod(),
                                slot.startWeek(), slot.endWeek(), slot.classroom())))
                .toList();
    }
}
