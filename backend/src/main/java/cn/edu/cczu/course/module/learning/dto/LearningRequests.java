package cn.edu.cczu.course.module.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 定义成绩、资料和公告表单的提交结构及字段限制。 */
public final class LearningRequests {
    // 这是用于组织相关类型或静态方法的工具类，无需创建实例。
    private LearningRequests() { }
    // 一名学生对应的可编辑成绩字段，版本号用于发现并发修改。
    // 空成绩可以留作草稿，非空成绩受 0 到 100 的范围校验。版本号会随保存或发布变化。
    public record GradeEntry(@NotNull @Positive Long enrollmentId,
                              @DecimalMin(value="0",message="成绩不能小于 0") @DecimalMax(value="100",message="成绩不能超过 100") @Digits(integer=3,fraction=2) BigDecimal usualScore,
                              @DecimalMin(value="0",message="成绩不能小于 0") @DecimalMax(value="100",message="成绩不能超过 100") @Digits(integer=3,fraction=2) BigDecimal examScore,
                              @Size(max=500,message="成绩备注不能超过 500 字") String remark,
                              @Min(0) int revision) { }
    // 一次操作提交的全班成绩条目与统一平时分权重。
    public record GradeBatch(@Min(0) @Max(100) int usualWeight,
                              @NotEmpty(message="没有可保存的成绩") @Size(max=1000) List<@NotNull @Valid GradeEntry> entries) { }
    // 用户可自行编辑的联系方式和简介字段。
    public record ProfileRequest(@NotNull @Email(message="邮箱格式不正确") @Size(max=120) String email,
                                  @NotNull @Pattern(regexp="[0-9+()\\- ]{0,30}",message="联系电话格式不正确") String phone,
                                  @NotNull @Size(max=500,message="个人简介不能超过 500 字") String bio) { }
    // 管理员提交的公告内容、范围、状态和版本。
    public record NoticeRequest(@NotBlank(message="请输入公告标题") @Size(max=150,message="公告标题不能超过 150 字") String title,
                                 @NotBlank(message="请输入公告内容") @Size(max=5000,message="公告内容不能超过 5000 字") String content,
                                 @NotNull @Pattern(regexp="ALL|ADMIN|TEACHER|STUDENT",message="公告接收角色不正确") String audience,
                                 @NotNull @Pattern(regexp="DRAFT|PUBLISHED",message="公告状态不正确") String status,
                                 boolean pinned, LocalDateTime visibleFrom, LocalDateTime expiresAt,
                                 @Min(0) int revision) { }
    // 提交用户刚看到的公告版本，防止把更新后的内容误标为已读。
    public record ReadNoticeRequest(@Min(1) int revision) { }
}
