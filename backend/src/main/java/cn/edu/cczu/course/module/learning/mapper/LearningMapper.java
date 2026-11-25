package cn.edu.cczu.course.module.learning.mapper;

import cn.edu.cczu.course.module.learning.model.LearningModels.*;
import cn.edu.cczu.course.module.academic.model.AcademicModels.ClassCount;
import cn.edu.cczu.course.module.learning.dto.LearningRequests.*;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 负责成绩、个人资料和公告的数据库读写，包括版本检查和公告已读状态。 */
public interface LearningMapper {
    // 以仍在选课的学生为准读取成绩。还没录入成绩的人也会出现在成绩表中。
    @Select("""
        SELECT e.id AS enrollment_id,s.id AS student_id,s.student_no,u.display_name AS student_name,
        s.major,s.class_name,g.usual_score,g.exam_score,COALESCE(g.usual_weight,30) AS usual_weight,
        g.total_score,COALESCE(g.status,'DRAFT') AS status,COALESCE(g.remark,'') AS remark,
        g.updated_at,g.published_at,COALESCE(g.revision,0) AS revision
        FROM enrollment e JOIN student s ON s.id=e.student_id JOIN sys_user u ON u.id=s.user_id
        LEFT JOIN student_grade g ON g.enrollment_id=e.id
        WHERE e.teaching_class_id=#{id} AND e.status='ENROLLED' ORDER BY s.student_no
        """) List<GradeRow> gradeRows(Long id);
    @Select("<script>SELECT e.teaching_class_id, COUNT(*) AS total FROM student_grade g"
            + " JOIN enrollment e ON e.id=g.enrollment_id WHERE g.status='PUBLISHED' AND e.teaching_class_id IN"
            + " <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " GROUP BY e.teaching_class_id</script>")
    // 按班级计算已发布的成绩数量，供选课检查。
    List<ClassCount> publishedCounts(@Param("ids") List<Long> ids);
    // 检查一条选课记录是否已有成绩输入或发布状态，供退课校验。
    @Select("SELECT COUNT(*) FROM student_grade WHERE enrollment_id=#{id} AND (usual_score IS NOT NULL OR exam_score IS NOT NULL OR status='PUBLISHED')")
    long recordedCount(Long id);
    // 为一个选课记录首次保存成绩。
    @Insert("""
        INSERT INTO student_grade(enrollment_id,usual_score,exam_score,usual_weight,total_score,remark,updated_by,updated_at)
        VALUES(#{entry.enrollmentId},#{entry.usualScore,jdbcType=DECIMAL},#{entry.examScore,jdbcType=DECIMAL},#{weight},#{total,jdbcType=DECIMAL},#{entry.remark},#{userId},#{now})
        """) int insertGrade(@Param("entry") GradeEntry entry,@Param("weight") int weight,@Param("total") BigDecimal total,@Param("userId") Long userId,@Param("now") LocalDateTime now);
    // 只有成绩仍是草稿且版本一致时才更新，同时将版本加一。
    @Update("""
        UPDATE student_grade SET usual_score=#{entry.usualScore,jdbcType=DECIMAL},exam_score=#{entry.examScore,jdbcType=DECIMAL},usual_weight=#{weight},
        total_score=#{total,jdbcType=DECIMAL},remark=#{entry.remark},updated_by=#{userId},updated_at=#{now},revision=revision+1
        WHERE enrollment_id=#{entry.enrollmentId} AND revision=#{entry.revision} AND status='DRAFT'
        """) int updateGrade(@Param("entry") GradeEntry entry,@Param("weight") int weight,@Param("total") BigDecimal total,@Param("userId") Long userId,@Param("now") LocalDateTime now);
    // 统一更新全班仍有效的选课成绩的发布状态与修改版本。
    @Update("""
        UPDATE student_grade SET status=#{status},published_at=#{publishedAt,jdbcType=TIMESTAMP},updated_at=#{now},updated_by=#{userId},revision=revision+1
        WHERE enrollment_id IN (SELECT id FROM enrollment WHERE teaching_class_id=#{classId} AND status='ENROLLED')
        """) int gradeStatus(@Param("classId") Long classId,@Param("status") String status,@Param("publishedAt") LocalDateTime publishedAt,@Param("now") LocalDateTime now,@Param("userId") Long userId);
    // 只查询当前学生有效选课且已经发布的成绩。
    @Select("""
        SELECT e.id AS enrollment_id,tc.id AS teaching_class_id,tc.class_code,c.course_code,c.name AS course_name,
        tu.display_name AS teacher_name,se.id AS semester_id,se.name AS semester_name,c.credits,
        g.usual_score,g.exam_score,g.usual_weight,g.total_score,g.remark,g.published_at
        FROM enrollment e JOIN student s ON s.id=e.student_id JOIN sys_user u ON u.id=s.user_id
        JOIN teaching_class tc ON tc.id=e.teaching_class_id JOIN course c ON c.id=tc.course_id
        JOIN teacher t ON t.id=tc.teacher_id JOIN sys_user tu ON tu.id=t.user_id JOIN semester se ON se.id=tc.semester_id
        JOIN student_grade g ON g.enrollment_id=e.id
        WHERE u.username=#{username} AND e.status='ENROLLED' AND g.status='PUBLISHED'
        AND (#{semesterId} IS NULL OR se.id=#{semesterId}) ORDER BY se.start_date DESC,c.course_code
        """) List<StudentGrade> myGrades(@Param("username") String username,@Param("semesterId") Long semesterId);
    // 按用户名或当前会话读取个人资料。
    @Select("""
        SELECT u.username,u.display_name,u.role,COALESCE(p.email,'') AS email,COALESCE(p.phone,'') AS phone,
        COALESCE(p.bio,'') AS bio,s.student_no,s.major,s.class_name,t.teacher_no,t.department,p.updated_at
        FROM sys_user u LEFT JOIN student s ON s.user_id=u.id LEFT JOIN teacher t ON t.user_id=u.id
        LEFT JOIN user_profile p ON p.user_id=u.id WHERE u.username=#{username}
        """) Profile profile(String username);
    // 检查该账号是否已经有个人资料记录。
    @Select("SELECT COUNT(*) FROM user_profile WHERE user_id=#{id}") long profileExists(Long id);
    // 为首次保存个人资料的账号新增一行资料记录。
    @Insert("INSERT INTO user_profile(user_id,email,phone,bio,updated_at) VALUES(#{id},#{body.email},#{body.phone},#{body.bio},#{now})")
    int insertProfile(@Param("id") Long id,@Param("body") ProfileRequest body,@Param("now") LocalDateTime now);
    // 保存用户可编辑的个人资料字段。
    @Update("UPDATE user_profile SET email=#{body.email},phone=#{body.phone},bio=#{body.bio},updated_at=#{now} WHERE user_id=#{id}")
    int updateProfile(@Param("id") Long id,@Param("body") ProfileRequest body,@Param("now") LocalDateTime now);

    // 把公告与当前用户的已读记录关联，版本一致时才显示该公告已读。
    // 左连接保留没有读过的公告。更新公告版本后，之前的已读记录仍保存但不再显示已读。
    String NOTICE = """
        SELECT a.id,a.title,a.content,a.audience,a.status,a.pinned,a.visible_from,a.expires_at,a.published_at,
        u.display_name AS author_name,a.created_at,a.updated_at,a.revision,
        CASE WHEN r.notice_revision=a.revision THEN r.read_at ELSE NULL END AS read_at
        FROM announcement a JOIN sys_user u ON u.id=a.author_id
        LEFT JOIN announcement_read r ON r.announcement_id=a.id AND r.user_id=#{userId}
        """;
    // 读取符合当前角色、发布状态和时间范围的公告列表。
    @SelectProvider(type=NoticeSqlProvider.class,method="list")
    List<Notice> notices(@Param("userId") Long userId,@Param("role") String role,@Param("admin") boolean admin,
        @Param("pattern") String pattern,@Param("unread") boolean unread,@Param("now") LocalDateTime now,@Param("offset") int offset,@Param("size") int size);
    // 用与公告列表一致的筛选条件计算记录总数。
    @SelectProvider(type=NoticeSqlProvider.class,method="count")
    long countNotices(@Param("userId") Long userId,@Param("role") String role,@Param("admin") boolean admin,
        @Param("pattern") String pattern,@Param("unread") boolean unread,@Param("now") LocalDateTime now);
    // 按公告编号读取详情，并关联该用户的已读状态。
    @Select(NOTICE+" WHERE a.id=#{id}") Notice notice(@Param("id") Long id,@Param("userId") Long userId);
    // 保存新公告以及作者、创建时间和初始发布信息。
    @Insert("""
        INSERT INTO announcement(title,content,audience,status,pinned,visible_from,expires_at,published_at,author_id,created_at,updated_at)
        VALUES(#{body.title},#{body.content},#{body.audience},#{body.status},#{body.pinned},#{body.visibleFrom,jdbcType=TIMESTAMP},#{body.expiresAt,jdbcType=TIMESTAMP},#{published,jdbcType=TIMESTAMP},#{userId},#{now},#{now})
        """) int insertNotice(@Param("body") NoticeRequest body,@Param("userId") Long userId,@Param("published") LocalDateTime published,@Param("now") LocalDateTime now);
    // 按匹配的版本修改公告，同时把版本号加一。
    @Update("""
        UPDATE announcement SET title=#{body.title},content=#{body.content},audience=#{body.audience},status=#{body.status},
        pinned=#{body.pinned},visible_from=#{body.visibleFrom,jdbcType=TIMESTAMP},expires_at=#{body.expiresAt,jdbcType=TIMESTAMP},published_at=#{published,jdbcType=TIMESTAMP},
        updated_at=#{now},revision=revision+1 WHERE id=#{id} AND revision=#{body.revision}
        """) int updateNotice(@Param("id") Long id,@Param("body") NoticeRequest body,@Param("published") LocalDateTime published,@Param("now") LocalDateTime now);
    // 仅删除从未发布且版本匹配的草稿。
    @Delete("DELETE FROM announcement WHERE id=#{id} AND revision=#{revision} AND published_at IS NULL AND status='DRAFT'")
    int deleteNotice(@Param("id") Long id,@Param("revision") int revision);
    // 将已有已读记录更新到这次打开的版本与时间。
    @Update("UPDATE announcement_read SET notice_revision=#{revision},read_at=#{now} WHERE announcement_id=#{id} AND user_id=#{userId}")
    int updateRead(@Param("id") Long id,@Param("userId") Long userId,@Param("revision") int revision,@Param("now") LocalDateTime now);
    // 用户首次阅读公告时，建立该账号的已读记录。
    @Insert("INSERT INTO announcement_read(announcement_id,user_id,notice_revision,read_at) VALUES(#{id},#{userId},#{revision},#{now})")
    int insertRead(@Param("id") Long id,@Param("userId") Long userId,@Param("revision") int revision,@Param("now") LocalDateTime now);
}
