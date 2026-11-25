package cn.edu.cczu.course.module.academic.service;

import cn.edu.cczu.course.module.academic.dto.AcademicRequests.*;
import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import cn.edu.cczu.course.module.audit.service.AuditService;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.module.user.model.UserAccount;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.*;

/** 处理教务资料维护和教学班排课，检查引用关系及历史记录对修改的限制。 */
@Service
@Transactional(readOnly = true)
public class ManagementService {
    private final AcademicMapper mapper;
    private final UserMapper userMapper;
    private final PasswordEncoder encoder;
    private final AcademicSupport support;
    private final AuditService audit;

    public ManagementService(AcademicMapper mapper, UserMapper userMapper, PasswordEncoder encoder,
                             AcademicSupport support, AuditService audit) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.encoder = encoder;
        this.support = support;
        this.audit = audit;
    }

    // 按关键词和页码读取学生档案，供管理列表显示。
    public PageResult<Student> students(int page, int size, String keyword) {
        return new PageResult<>(mapper.students(pattern(keyword), offset(page,size), size), mapper.countStudents(pattern(keyword)), page, size);
    }
    // 按关键词和页码读取教师档案，供管理列表显示。
    public PageResult<Teacher> teachers(int page, int size, String keyword) {
        return new PageResult<>(mapper.teachers(pattern(keyword), offset(page,size), size), mapper.countTeachers(pattern(keyword)), page, size);
    }
    // 按编号或课程名筛选课程库，并返回当前页。
    public PageResult<Course> courses(int page, int size, String keyword) {
        return new PageResult<>(mapper.courses(pattern(keyword), offset(page,size), size), mapper.countCourses(pattern(keyword)), page, size);
    }
    // 查询学期。管理列表支持分页和关键词筛选。
    public PageResult<Semester> semesters(int page, int size, String keyword) {
        return new PageResult<>(mapper.semesters(pattern(keyword), offset(page,size), size), mapper.countSemesters(pattern(keyword)), page, size);
    }
    // 按条件查询教学班，供列表或下拉框使用。
    public PageResult<ClassView> classes(int page, int size, String keyword, Long semesterId) {
        return new PageResult<>(support.classViews(mapper.classes(pattern(keyword), semesterId, offset(page,size), size)),
                mapper.countClasses(pattern(keyword), semesterId), page, size);
    }
    // 按关键词、学期和选退课状态查询后台选课记录。
    public PageResult<Enrollment> enrollments(int page, int size, String keyword, Long semesterId, String status) {
        valid(status == null || List.of("ENROLLED","DROPPED").contains(status), "选课状态不正确");
        return new PageResult<>(mapper.enrollments(pattern(keyword), semesterId, status, offset(page,size), size),
                mapper.countEnrollments(pattern(keyword), semesterId, status), page, size);
    }
    // 为管理表单准备教师、课程和学期等下拉选项。
    public Map<String,Object> options() {
        return Map.of("courses", mapper.allCourses(), "teachers", mapper.activeTeachers(), "semesters", mapper.allSemesters());
    }

    // 新建学生登录账号与学籍档案。
    @Transactional
    public Student createStudent(StudentRequest body) {
        createAccount(body.studentNo(), body.displayName(), "STUDENT", body.enabled(), body.initialPassword());
        mapper.insertStudent(userMapper.findByUsername(body.studentNo()).id(), body);
        return mapper.studentByUsername(body.studentNo());
    }
    // 按学生编号修改学籍和账号状态。
    @Transactional
    public Student updateStudent(Long id, StudentRequest body) {
        var existing = require(mapper.student(id), "学生不存在");
        check(existing.studentNo().equals(body.studentNo()), "学号创建后不能修改");
        mapper.updateStudent(id, body);
        // 显示名和启用状态保存在账号表中，专业等字段保存在档案表中。同一事务将改动一起提交。
        mapper.updatePersonAccount(existing.userId(), body.displayName().trim(), body.enabled());
        auditAccountStatus("STUDENT", id, existing.enabled(), body.enabled());
        return mapper.student(id);
    }
    // 新建教师登录账号与任职档案。
    @Transactional
    public Teacher createTeacher(TeacherRequest body) {
        createAccount(body.teacherNo(), body.displayName(), "TEACHER", body.enabled(), body.initialPassword());
        mapper.insertTeacher(userMapper.findByUsername(body.teacherNo()).id(), body);
        return mapper.teacherByUsername(body.teacherNo());
    }
    // 按教师编号修改任职信息和账号状态。
    @Transactional
    public Teacher updateTeacher(Long id, TeacherRequest body) {
        var existing = require(mapper.teacher(id), "教师不存在");
        check(existing.teacherNo().equals(body.teacherNo()), "工号创建后不能修改");
        mapper.updateTeacher(id, body);
        mapper.updatePersonAccount(existing.userId(), body.displayName().trim(), body.enabled());
        auditAccountStatus("TEACHER", id, existing.enabled(), body.enabled());
        return mapper.teacher(id);
    }
    // 根据管理资源找到相应账号，校验并保存新密码。
    @Transactional
    public void resetPassword(String resource, Long id, String password) {
        validatePassword(password);
        // 根据后台资源类型查出对应的账号编号，再统一执行账号密码更新。
        Long userId = switch (resource) {
            case "students" -> require(mapper.student(id), "学生不存在").userId();
            case "teachers" -> require(mapper.teacher(id), "教师不存在").userId();
            default -> throw new IllegalArgumentException("不支持的账号资源");
        };
        mapper.updatePassword(userId, encoder.encode(password));
        audit.recordCurrentActor("PASSWORD_RESET", resource.equals("students") ? "STUDENT" : "TEACHER", id, "管理员重置密码");
    }
    // 仅在启用状态发生变化时记录审计信息。
    private void auditAccountStatus(String targetType, Long id, boolean previous, boolean current) {
        if (previous != current) {
            audit.recordCurrentActor("ACCOUNT_UPDATE", targetType, id, "启用状态 " + previous + " → " + current);
        }
    }
    // 检查用户名是否重复并对密码做哈希，然后创建账号。
    private void createAccount(String username, String name, String role, boolean enabled, String password) {
        validatePassword(password);
        check(userMapper.findByUsername(username) == null, "该学号、工号或账号已存在");
        userMapper.insert(new UserAccount(null, username, encoder.encode(password), name.trim(), role, enabled));
    }

    // 新增课程库记录，供后续开设教学班。
    @Transactional
    public Course createCourse(CourseRequest body) {
        mapper.insertCourse(body);
        return mapper.courseByCode(body.courseCode());
    }
    // 更新既有课程的基础信息。
    @Transactional
    public Course updateCourse(Long id, CourseRequest body) {
        var course = require(mapper.course(id), "课程不存在");
        // 课程学分变更会影响已选学生的总学分。先锁住课程涉及的学期，避免改学分时有新选课同时写入。
        support.lockSemesters(mapper.courseSemesters(id));
        if (mapper.courseReferences(id) > 0) {
            check(course.courseCode().equals(body.courseCode()) && course.credits().compareTo(body.credits()) == 0
                    && course.hours() == body.hours(), "课程已被开课引用，只允许修改名称与简介");
        }
        mapper.updateCourse(id, body);
        return mapper.course(id);
    }
    // 删除指定课程前检查它是否还被教学班引用。
    @Transactional
    public void deleteCourse(Long id) {
        require(mapper.course(id), "课程不存在");
        check(mapper.courseReferences(id) == 0, "该课程已有教学班，不能删除");
        mapper.deleteCourse(id);
    }
    // 新建学期并校验教学日期和选课窗口。
    @Transactional
    public Semester createSemester(SemesterRequest body) {
        validateSemester(body);
        mapper.insertSemester(body);
        return mapper.semesterByName(body.name());
    }
    // 修改学期的日期、选课时间或学分上限。
    @Transactional
    public Semester updateSemester(Long id, SemesterRequest body) {
        require(mapper.lockSemester(id), "学期不存在");
        validateSemester(body);
        // 调整学分上限前读取当前最高的有效选课学分，已有学生超过新上限时拒绝保存。
        check(mapper.highestSelectedCredits(id).compareTo(body.maxCredits()) <= 0, "新的学分上限低于已有学生的有效选课学分");
        mapper.updateSemester(id, body);
        return mapper.semester(id);
    }
    // 删除学期前检查是否还有教学班引用它。
    @Transactional
    public void deleteSemester(Long id) {
        require(mapper.lockSemester(id), "学期不存在");
        check(mapper.semesterReferences(id) == 0, "学期已有教学班，不能删除");
        mapper.deleteSemester(id);
    }
    // 分别检查教学日期和允许选课时间的起止顺序。
    private void validateSemester(SemesterRequest body) {
        valid(!body.endDate().isBefore(body.startDate()), "学期结束日期不能早于开始日期");
        valid(!body.selectionEnd().isBefore(body.selectionStart()), "选课结束时间不能早于开始时间");
    }

    // 保存教学班及其上课安排。
    @Transactional
    public ClassView createClass(ClassRequest body) {
        support.lockSemesters(List.of(body.semesterId()));
        validateClass(body, -1L);
        mapper.insertClass(body);
        var row = mapper.classByCode(body.classCode());
        replaceSchedules(row.id(), body.schedules());
        return support.classView(row);
    }
    // 更新指定教学班，并根据已有选课记录限制可修改内容。
    @Transactional
    public ClassView updateClass(Long id, ClassRequest body) {
        var initial = require(mapper.teachingClass(id), "教学班不存在");
        support.lockSemesters(List.of(initial.semesterId(), body.semesterId()));
        require(mapper.lockClass(id), "教学班不存在");
        var existing = require(mapper.teachingClass(id), "教学班不存在");
        check(existing.semesterId().equals(initial.semesterId()), "教学班已被其他操作修改，请刷新后重试");
        long selected = mapper.selectedCount(id);
        // 容量即使可以编辑，也必须容纳所有当前仍在这个教学班选课的学生。
        check(body.capacity() >= selected, "容量不能小于当前有效选课人数");
        // 只要有过选课记录，就固定课程、教师、学期和上课安排，避免改写学生的历史课表。
        if (mapper.enrollmentHistory(id) > 0) {
            var current = mapper.schedules(id).stream().map(s -> new ScheduleRequest(s.weekday(), s.startPeriod(), s.endPeriod(), s.startWeek(), s.endWeek(), s.classroom())).toList();
            check(existing.classCode().equals(body.classCode()) && existing.courseId().equals(body.courseId())
                    && existing.teacherId().equals(body.teacherId()) && existing.semesterId().equals(body.semesterId())
                    && current.equals(body.schedules()), "已有选课历史，仅允许调整容量与开课状态");
        }
        check(selected == 0 || !body.status().equals("DRAFT"), "已有学生选课的教学班不能改回草稿，可关闭选课");
        validateClass(body, id);
        mapper.updateClass(id, body);
        replaceSchedules(id, body.schedules());
        return support.classView(mapper.teachingClass(id));
    }
    // 仅在教学班没有选退课历史时删除班级及排课。
    @Transactional
    public void deleteClass(Long id) {
        var row = require(mapper.teachingClass(id), "教学班不存在");
        support.lockSemesters(List.of(row.semesterId()));
        require(mapper.lockClass(id), "教学班不存在");
        check(mapper.enrollmentHistory(id) == 0, "教学班已有选退课历史，不能删除，可改为关闭状态");
        mapper.deleteSchedules(id);
        mapper.deleteClass(id);
        audit.recordCurrentActor("CLASS_DELETE", "TEACHING_CLASS", id, "删除教学班 " + row.classCode());
    }
    // 验证课程、教师及排课。发布前还要检查教师和教室占用。
    private void validateClass(ClassRequest body, Long excludedId) {
        require(mapper.course(body.courseId()), "所选课程不存在");
        var teacher = require(mapper.teacher(body.teacherId()), "所选教师不存在");
        if (body.status().equals("PUBLISHED")) {
            check(teacher.enabled(), "停用教师不能发布教学班");
            check(!body.schedules().isEmpty(), "发布前必须至少设置一个上课安排");
        }
        // 先检查本班每条排课的起止范围，再逐条比较它们是否重叠。
        var schedules = body.schedules();
        for (int i = 0; i < schedules.size(); i++) {
            var slot = schedules.get(i);
            valid(slot.endPeriod() >= slot.startPeriod(), "结束节次不能早于开始节次");
            valid(slot.endWeek() >= slot.startWeek(), "结束周次不能早于开始周次");
            for (int j = 0; j < i; j++) check(!overlaps(slot, schedules.get(j)), "同一教学班的上课安排不能互相重叠");
        }
        if (body.status().equals("PUBLISHED")) {
            // 发布时还要检查同学期其他教学班，防止同一教师或教室在相同时间被占用。
            for (var other : mapper.resourceSlots(body.semesterId(), excludedId)) {
                for (var slot : schedules) {
                    if (overlaps(slot.weekday(), slot.startPeriod(), slot.endPeriod(), slot.startWeek(), slot.endWeek(),
                            other.weekday(), other.startPeriod(), other.endPeriod(), other.startWeek(), other.endWeek())) {
                        check(!other.teacherId().equals(body.teacherId()), "教师上课时间与教学班 " + other.classCode() + " 冲突");
                        check(!other.classroom().strip().equalsIgnoreCase(slot.classroom().strip()), "教室与教学班 " + other.classCode() + " 的上课时间冲突");
                    }
                }
            }
        }
    }
    // 在当前事务中先移除旧排课，再写入提交的新安排。
    private void replaceSchedules(Long id, List<ScheduleRequest> schedules) {
        // 当前操作有事务包裹。先移除原安排，再写入提交的新时段，任何一步失败都会回滚。
        mapper.deleteSchedules(id);
        for (var schedule : schedules) mapper.insertSchedule(id, schedule);
    }
}
