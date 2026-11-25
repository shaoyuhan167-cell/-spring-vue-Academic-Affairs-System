package cn.edu.cczu.course.module.academic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 写入请求与数据库查询模型分离，避免客户端覆盖权限和内部状态。 */
public final class AcademicRequests {
    // 这是用于组织相关类型或静态方法的工具类，无需创建实例。
    private AcademicRequests() { }

    // 描述新建或编辑学生时提交的账号与学籍字段。
    public record StudentRequest(
            @NotBlank(message = "请输入学号") @Pattern(regexp = "[A-Za-z0-9_-]{1,30}", message = "学号仅支持字母、数字、下划线与短横线") String studentNo,
            @NotBlank(message = "请输入姓名") @Size(max = 50) String displayName,
            @NotBlank(message = "请输入专业") @Size(max = 100) String major,
            @NotBlank(message = "请输入班级") @Size(max = 100) String className,
            @NotNull(message = "请选择账号状态") Boolean enabled,
            @Size(max = 72, message = "初始密码过长") String initialPassword) { }
    // 描述新建或编辑教师时提交的账号与任职字段。
    public record TeacherRequest(
            @NotBlank(message = "请输入工号") @Pattern(regexp = "[A-Za-z0-9_-]{1,30}", message = "工号仅支持字母、数字、下划线与短横线") String teacherNo,
            @NotBlank(message = "请输入姓名") @Size(max = 50) String displayName,
            @NotBlank(message = "请输入院系") @Size(max = 100) String department,
            @NotNull(message = "请选择账号状态") Boolean enabled,
            @Size(max = 72, message = "初始密码过长") String initialPassword) { }
    // 约束课程编号、名称、学分和学时等基础数据。
    public record CourseRequest(
            @NotBlank(message = "请输入课程编号") @Pattern(regexp = "[A-Za-z0-9_-]{1,30}", message = "课程编号格式不正确") String courseCode,
            @NotBlank(message = "请输入课程名称") @Size(max = 100) String name,
            @NotNull(message = "请输入学分") @DecimalMin(value = "0.1", message = "学分必须大于零") @DecimalMax(value = "30", message = "单门课程学分不能超过 30") @Digits(integer = 2, fraction = 1) BigDecimal credits,
            @Min(value = 1, message = "学时必须大于零") @Max(value = 512, message = "学时不能超过 512") int hours,
            @Size(max = 1000) String description) { }
    // 收集学期日期、选课起止时间和可选学分上限。
    public record SemesterRequest(
            @NotBlank(message = "请输入学期名称") @Size(max = 50) String name,
            @NotNull(message = "请选择学期开始日期") LocalDate startDate,
            @NotNull(message = "请选择学期结束日期") LocalDate endDate,
            @NotNull(message = "请选择选课开始时间") LocalDateTime selectionStart,
            @NotNull(message = "请选择选课结束时间") LocalDateTime selectionEnd,
            @NotNull(message = "请输入学分上限") @DecimalMin("0.1") @DecimalMax("100") @Digits(integer = 3, fraction = 1) BigDecimal maxCredits) { }
    // 描述一段上课安排所需的星期、节次、周次和教室。
    public record ScheduleRequest(
            @Min(1) @Max(7) int weekday,
            @Min(1) @Max(12) int startPeriod,
            @Min(1) @Max(12) int endPeriod,
            @Min(1) @Max(30) int startWeek,
            @Min(1) @Max(30) int endWeek,
            @NotBlank(message = "请输入上课地点") @Size(max = 100) String classroom) { }
    // 收集开设教学班所需的课程、教师、学期、容量、状态和排课。
    // 表单提交包含教学班基础字段及排课列表。字段边界由注解处理，教室冲突等规则交给业务层判断。
    public record ClassRequest(
            @NotBlank(message = "请输入教学班编号") @Pattern(regexp = "[A-Za-z0-9_-]{1,40}", message = "教学班编号格式不正确") String classCode,
            @NotNull @Positive Long courseId, @NotNull @Positive Long teacherId, @NotNull @Positive Long semesterId,
            @Min(value = 1, message = "容量必须大于零") @Max(1000) int capacity,
            @NotNull @Pattern(regexp = "DRAFT|PUBLISHED|CLOSED", message = "开课状态不正确") String status,
            @NotNull @Size(max = 12) List<@Valid ScheduleRequest> schedules) { }
    // 接收一次单独选课的教学班编号。
    public record EnrollRequest(@NotNull @Positive Long teachingClassId) { }
    // 接收一批计划一起选择的教学班编号。
    // 请求包含 1 到 20 个教学班编号。服务层会进一步检查是否有重复编号和互斥课程。
    public record BatchEnrollRequest(
            @NotNull(message = "请选择教学班") @Size(min = 1, max = 20, message = "每次请选择 1～20 个教学班")
            List<@NotNull @Positive Long> teachingClassIds) { }
    // 接收管理员重置账号时输入的新密码。
    public record PasswordRequest(@NotBlank(message = "请输入新密码") String newPassword) { }
    // 接收用户修改密码时填写的当前密码和新密码。
    public record ChangePasswordRequest(@NotBlank(message = "请输入原密码") String currentPassword,
                                        @NotBlank(message = "请输入新密码") String newPassword) { }
}
