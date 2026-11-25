package cn.edu.cczu.course.module.learning.model;

import cn.edu.cczu.course.module.academic.model.AcademicModels.ClassView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 成绩、资料与公告的对外查询模型。 */
public final class LearningModels {
    // 这是用于组织相关类型或静态方法的工具类，无需创建实例。
    private LearningModels() { }
    // 成绩表的一行，合并学生信息、成绩内容和当前版本。
    public record GradeRow(Long enrollmentId, Long studentId, String studentNo, String studentName,
                           String major, String className, BigDecimal usualScore, BigDecimal examScore,
                           int usualWeight, BigDecimal totalScore, String status, String remark,
                           LocalDateTime updatedAt, LocalDateTime publishedAt, int revision) { }
    // 一个教学班的成绩表及其录入、发布和成绩分布统计。
    public record GradeBook(ClassView teachingClass, int usualWeight, List<GradeRow> rows,
                            int completeCount, int publishedCount, BigDecimal average, int passCount) { }
    // 一条面向学生展示的已发布成绩。
    public record StudentGrade(Long enrollmentId, Long teachingClassId, String classCode, String courseCode,
                               String courseName, String teacherName, Long semesterId, String semesterName,
                               BigDecimal credits, BigDecimal usualScore, BigDecimal examScore, int usualWeight,
                               BigDecimal totalScore, String remark, LocalDateTime publishedAt) { }
    // 学生已发布成绩列表及整体统计，附有未发布门数。
    // 学生端用这个结构同时显示已发布明细、仍未发布的门数及加权统计。
    public record GradeSummary(List<StudentGrade> records, long pendingCount, BigDecimal average,
                               BigDecimal earnedCredits, BigDecimal totalCredits, BigDecimal gpa) { }
    // 合并账号基础信息、学籍或任职信息及个人联系方式。
    public record Profile(String username, String displayName, String role, String email, String phone,
                          String bio, String studentNo, String major, String className, String teacherNo,
                          String department, LocalDateTime updatedAt) { }
    // 公告内容、发布时间和当前账号对应的已读信息。
    public record Notice(Long id, String title, String content, String audience, String status, boolean pinned,
                         LocalDateTime visibleFrom, LocalDateTime expiresAt, LocalDateTime publishedAt,
                         String authorName, LocalDateTime createdAt, LocalDateTime updatedAt,
                         int revision, LocalDateTime readAt) { }
}
