package cn.edu.cczu.course.module.academic.service;

import cn.edu.cczu.course.common.exception.BusinessException;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.ScheduleRequest;
import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Function;

/** 集中处理服务之间共用的学期时间判断、排课冲突判断和教学班数据转换。 */
@Component
public class AcademicSupport {
    private final AcademicMapper mapper;
    private final Clock clock;
    private static final int SCHEDULE_BATCH_SIZE = 1000;

    public AcademicSupport(AcademicMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    // 从统一时钟获取当前时间，供选课窗口和业务时间戳使用。
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    // 数据库查询结果为空时抛出资源不存在错误，成功时返回查到的对象。
    public static <T> T require(T value, String message) {
        if (value == null) throw new BusinessException(HttpStatus.NOT_FOUND, message);
        return value;
    }
    // 条件不成立时返回业务冲突，例如教学班满员或当前状态不允许提交。
    public static void check(boolean condition, String message) {
        if (!condition) throw new BusinessException(HttpStatus.CONFLICT, message);
    }
    // 条件不成立时返回参数错误，例如页码越界或日期顺序有误。
    public static void valid(boolean condition, String message) {
        if (!condition) throw new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
    // 先验证页码和每页条数，再算出查询应跳过的记录数。
    public static int offset(int page, int size) {
        valid(page >= 1 && page <= 100000 && size >= 1 && size <= 100, "页码应为 1～100000，每页条数应为 1～100");
        // 页面页码从 1 开始，数据库从 0 条偏移开始，所以第一页跳过 0 条。
        return (page - 1) * size;
    }
    // 把搜索词变成模糊匹配条件，并转义用户输入里的 SQL 通配符。
    public static String pattern(String keyword) {
        // 空搜索词表示没有过滤条件。有输入时，调用者使用 ESCAPE 规则按字面查找百分号等字符。
        if (keyword == null || keyword.isBlank()) return null;
        valid(keyword.length() <= 100, "搜索内容不能超过 100 个字符");
        return "%" + keyword.trim().replace("~", "~~").replace("%", "~%")
                .replace("_", "~_").replace("[", "~[") + "%";
    }
    // 校验密码的字符组成和 UTF-8 长度，以符合 BCrypt 的输入限制。
    public static void validatePassword(String password) {
        valid(password != null && password.length() >= 10
                && password.getBytes(StandardCharsets.UTF_8).length <= 72, "密码至少 10 个字符，且 UTF-8 长度不能超过 72 字节");
        valid(password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit), "密码必须同时包含字母和数字");
    }
    // 当前时间处于选课起止范围内时返回有效，包含起止时刻。
    public boolean selectionOpen(Semester semester) {
        var current = now();
        return !current.isBefore(semester.selectionStart()) && !current.isAfter(semester.selectionEnd());
    }
    // 在选课窗口之外拒绝选课或退课请求。
    public void requireSelectionOpen(Semester semester) {
        check(selectionOpen(semester), "当前不在该学期允许选退课的时间范围内");
    }
    // 为一条教学班记录加载课表和当前是否可选的状态。
    public ClassView classView(ClassRow row) {
        var semester = require(mapper.semester(row.semesterId()), "学期不存在");
        return ClassView.from(row, mapper.schedules(row.id()), selectionOpen(semester));
    }
    // 批量读取课表并组装教学班列表，避免每条记录单独查一次数据库。
    public List<ClassView> classViews(List<ClassRow> rows) {
        // 列表没结果时直接返回空集合，避免再查学期、排课或构造空的 IN 条件。
        if (rows.isEmpty()) return List.of();
        var semesters = mapper.allSemesters().stream().collect(Collectors.toMap(Semester::id, Function.identity()));
        var schedules = schedulesByClass(rows.stream().map(ClassRow::id).toList());
        return rows.stream().map(row -> {
            var semester = require(semesters.get(row.semesterId()), "学期不存在");
            return ClassView.from(row, schedules.getOrDefault(row.id(), List.of()), selectionOpen(semester));
        }).toList();
    }

    // 去掉重复班级编号，分批查询排课并按班级分组。
    public Map<Long, List<Schedule>> schedulesByClass(List<Long> classIds) {
        var ids = classIds.stream().distinct().toList();
        var schedules = new ArrayList<Schedule>();
        // 分片以兼容 SQL Server 的单语句参数上限。
        for (int start = 0; start < ids.size(); start += SCHEDULE_BATCH_SIZE) {
            schedules.addAll(mapper.schedulesOf(ids.subList(start, Math.min(start + SCHEDULE_BATCH_SIZE, ids.size()))));
        }
        return schedules.stream().collect(Collectors.groupingBy(Schedule::teachingClassId));
    }

    // 按学期编号顺序加锁，让并发业务以一致顺序占用记录。
    public void lockSemesters(List<Long> ids) {
        // 所有教学写操作先按编号锁学期，再锁学生及教学班，统一锁顺序。
        ids.stream().distinct().sorted().forEach(id -> require(mapper.lockSemester(id), "学期不存在"));
    }

    // 同一天且节次和周次都相交时，两段排课才算冲突。
    public static boolean overlaps(int weekdayA, int firstPeriodA, int lastPeriodA, int firstWeekA, int lastWeekA,
                                   int weekdayB, int firstPeriodB, int lastPeriodB, int firstWeekB, int lastWeekB) {
        // 星期一致后分别计算节次与周次是否有交集。只要其中一段错开，就允许两门课共存。
        return weekdayA == weekdayB && firstPeriodA <= lastPeriodB && firstPeriodB <= lastPeriodA
                && firstWeekA <= lastWeekB && firstWeekB <= lastWeekA;
    }
    // 从两份排课表单中取出时间范围，再调用通用冲突判断。
    public static boolean overlaps(ScheduleRequest first, ScheduleRequest second) {
        return overlaps(first.weekday(), first.startPeriod(), first.endPeriod(), first.startWeek(), first.endWeek(),
                second.weekday(), second.startPeriod(), second.endPeriod(), second.startWeek(), second.endWeek());
    }
}
