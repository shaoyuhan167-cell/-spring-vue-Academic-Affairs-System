package cn.edu.cczu.course.module.academic.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 教学业务的查询结果；账号密码不属于任何对外模型。 */
public final class AcademicModels {
    // 这是用于组织相关类型或静态方法的工具类，无需创建实例。
    private AcademicModels() { }

    // 把列表当前页的记录和分页信息一起返回。
    public record PageResult<T>(List<T> records, long total, int page, int size) { }
    // 管理列表中的学生档案，同时包含学籍、账号和启用状态。
    public record Student(Long id, Long userId, String studentNo, String displayName,
                          String major, String className, boolean enabled) { }
    // 管理列表中的教师档案，同时包含任职、账号和启用状态。
    public record Teacher(Long id, Long userId, String teacherNo, String displayName,
                          String department, boolean enabled) { }
    // 课程库的一门课程，可以按学期重复开设。
    public record Course(Long id, String courseCode, String name, BigDecimal credits, int hours, String description) { }
    // 一个教学周期及其选课窗口和学分上限。
    public record Semester(Long id, String name, LocalDate startDate, LocalDate endDate,
                           LocalDateTime selectionStart, LocalDateTime selectionEnd, BigDecimal maxCredits) { }
    // 一个教学班中连续若干节、跨指定周次的上课时段。
    public record Schedule(Long id, Long teachingClassId, int weekday, int startPeriod, int endPeriod,
                           int startWeek, int endWeek, String classroom) { }
    // 从班级、课程、学期和教师记录拼出的班级基础信息。
    // 这一结构对应查询得到的基本教学班字段，例如已选人数。页面需要的排课由 ClassView 补齐。
    public record ClassRow(Long id, String classCode, Long courseId, String courseCode, String courseName,
                           BigDecimal credits, int hours, Long teacherId, String teacherName, Long semesterId,
                           String semesterName, int capacity, String status, long selectedCount) { }
    // 面向页面的班级信息，在基础字段上增加排课列表和选课状态。
    public record ClassView(Long id, String classCode, Long courseId, String courseCode, String courseName,
                            BigDecimal credits, int hours, Long teacherId, String teacherName, Long semesterId,
                            String semesterName, int capacity, String status, long selectedCount,
                            List<Schedule> schedules, boolean selectionOpen) {
        // 将一条数据库查询记录和查到的排课合并成页面使用的教学班数据。
        public static ClassView from(ClassRow row, List<Schedule> schedules, boolean selectionOpen) {
            return new ClassView(row.id(), row.classCode(), row.courseId(), row.courseCode(), row.courseName(),
                    row.credits(), row.hours(), row.teacherId(), row.teacherName(), row.semesterId(),
                    row.semesterName(), row.capacity(), row.status(), row.selectedCount(), schedules, selectionOpen);
        }
    }
    // 选课关系及其关联信息，包含选择和退课时间。
    public record Enrollment(Long id, Long studentId, String studentNo, String studentName,
                             Long teachingClassId, String classCode, Long courseId, String courseName,
                             BigDecimal credits, String teacherName, Long semesterId, String semesterName,
                             String status, LocalDateTime selectedAt, LocalDateTime droppedAt) { }
    // 将选退课记录与其对应教学班的排课一起返回。
    public record EnrollmentView(Enrollment info, List<Schedule> schedules) { }
    // 选课校验所需的教学班快照，包含课程、学期、容量和教师状态。
    // 与列表显示用的 ClassView 相比，这个记录集中保存提交校验要用到的课程、开课状态和教师状态。
    public record SelectionClass(Long id, String classCode, Long courseId, String courseName, BigDecimal credits,
                                 Long semesterId, int capacity, String status, boolean teacherEnabled) { }
    // 计算冲突和累计学分时使用的有效选课摘要。
    public record ActiveSelection(Long teachingClassId, String classCode, Long courseId, String courseName,
                                  BigDecimal credits, Long semesterId) {
        // 将本次新选的教学班转成有效选课信息，让批次中后续课程能继续用它检查冲突。
        public static ActiveSelection from(SelectionClass row) {
            return new ActiveSelection(row.id(), row.classCode(), row.courseId(), row.courseName(), row.credits(), row.semesterId());
        }
    }
    // 在批量查询结果中连接教学班编号和已有选课记录编号。
    public record ClassEnrollmentId(Long teachingClassId, Long enrollmentId) { }
    // 承载一条按教学班分组的计数结果。
    public record ClassCount(Long teachingClassId, long total) { }
    // 教师或管理员查看名单时展示的学生字段。
    public record RosterStudent(Long id, String studentNo, String displayName, String major,
                                String className, LocalDateTime selectedAt) { }
    // 排课冲突检查所需的教室、教师和时间范围。
    public record ResourceSlot(Long teachingClassId, Long teacherId, String classCode, int weekday,
                               int startPeriod, int endPeriod, int startWeek, int endWeek, String classroom) { }
    // 学生课表中的一个上课时段及课程、任课教师和教室信息。
    public record TimetableItem(Long teachingClassId, String courseName, String classCode, String teacherName,
                                String semesterName, int weekday, int startPeriod, int endPeriod,
                                int startWeek, int endWeek, String classroom) { }
}
