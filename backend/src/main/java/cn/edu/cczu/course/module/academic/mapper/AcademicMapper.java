package cn.edu.cczu.course.module.academic.mapper;

import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 把教务操作对应到数据库查询和更新，包括档案、排课、选退课和并发控制所需的锁。 */
public interface AcademicMapper {
    // 按关键词和页码读取学生档案，供管理列表显示。
    @SelectProvider(type=AcademicSqlProvider.class, method="students")
    List<Student> students(@Param("pattern") String pattern, @Param("offset") int offset, @Param("size") int size);
    // 统计符合当前条件的学生总数，用于计算分页。
    @SelectProvider(type=AcademicSqlProvider.class, method="countStudents")
    long countStudents(@Param("pattern") String pattern);
    // 按关键词和页码读取教师档案，供管理列表显示。
    @SelectProvider(type=AcademicSqlProvider.class, method="teachers")
    List<Teacher> teachers(@Param("pattern") String pattern, @Param("offset") int offset, @Param("size") int size);
    // 统计符合当前条件的教师总数，用于计算分页。
    @SelectProvider(type=AcademicSqlProvider.class, method="countTeachers")
    long countTeachers(@Param("pattern") String pattern);
    // 按编号或课程名筛选课程库，并返回当前页。
    @SelectProvider(type=AcademicSqlProvider.class, method="courses")
    List<Course> courses(@Param("pattern") String pattern, @Param("offset") int offset, @Param("size") int size);
    // 统计符合当前条件的课程总数，用于计算分页。
    @SelectProvider(type=AcademicSqlProvider.class, method="countCourses")
    long countCourses(@Param("pattern") String pattern);
    // 查询学期。管理列表支持分页和关键词筛选。
    @SelectProvider(type=AcademicSqlProvider.class, method="semesters")
    List<Semester> semesters(@Param("pattern") String pattern, @Param("offset") int offset, @Param("size") int size);
    // 统计符合当前条件的学期总数，用于计算分页。
    @SelectProvider(type=AcademicSqlProvider.class, method="countSemesters")
    long countSemesters(@Param("pattern") String pattern);
    // 按条件查询教学班，供列表或下拉框使用。
    @SelectProvider(type=AcademicSqlProvider.class, method="classes")
    List<ClassRow> classes(@Param("pattern") String pattern, @Param("semesterId") Long semesterId,
                          @Param("offset") int offset, @Param("size") int size);
    // 统计符合当前条件的教学班总数，用于计算分页。
    @SelectProvider(type=AcademicSqlProvider.class, method="countClasses")
    long countClasses(@Param("pattern") String pattern, @Param("semesterId") Long semesterId);
    // 按关键词、学期和选退课状态查询后台选课记录。
    @SelectProvider(type=AcademicSqlProvider.class, method="enrollments")
    List<Enrollment> enrollments(@Param("pattern") String pattern, @Param("semesterId") Long semesterId,
                                @Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    // 统计符合当前条件的选课记录总数，用于计算分页。
    @SelectProvider(type=AcademicSqlProvider.class, method="countEnrollments")
    long countEnrollments(@Param("pattern") String pattern, @Param("semesterId") Long semesterId, @Param("status") String status);

    // 按学生档案编号加载档案及账号基本信息。
    @Select(AcademicSqlProvider.STUDENTS + " WHERE s.id=#{id}") Student student(Long id);
    // 从登录用户名找到对应的学生档案。
    @Select(AcademicSqlProvider.STUDENTS + " WHERE u.username=#{username}") Student studentByUsername(String username);
    // 按教师档案编号加载档案及账号基本信息。
    @Select(AcademicSqlProvider.TEACHERS + " WHERE t.id=#{id}") Teacher teacher(Long id);
    // 从登录用户名找到对应的教师档案。
    @Select(AcademicSqlProvider.TEACHERS + " WHERE u.username=#{username}") Teacher teacherByUsername(String username);
    // 列出启用的教师，供开课时选择。
    @Select(AcademicSqlProvider.TEACHERS + " WHERE u.enabled=1 ORDER BY t.teacher_no") List<Teacher> activeTeachers();
    // 按课程主键读取基础信息。
    @Select(AcademicSqlProvider.COURSES + " WHERE id=#{id}") Course course(Long id);
    // 列出课程库中的全部课程，供开课表单使用。
    @Select(AcademicSqlProvider.COURSES + " ORDER BY course_code") List<Course> allCourses();
    // 按学期主键读取选课时间、教学日期和学分限制。
    @Select(AcademicSqlProvider.SEMESTERS + " WHERE id=#{id}") Semester semester(Long id);
    // 按学期时间列出学期选项。
    @Select(AcademicSqlProvider.SEMESTERS + " ORDER BY start_date DESC, id DESC") List<Semester> allSemesters();
    // 按教学班编号查询开课、教师与学期信息。
    @Select(AcademicSqlProvider.CLASSES + " WHERE tc.id=#{id}") ClassRow teachingClass(Long id);
    @Select("<script>" + AcademicSqlProvider.CLASSES + " WHERE tc.status='PUBLISHED' AND u.enabled=1"
            + "<if test='semesterId != null'> AND tc.semester_id=#{semesterId}</if>"
            + " ORDER BY s.start_date DESC, tc.id</script>")
    // 仅返回已发布的教学班，可按学期限定。
    List<ClassRow> publishedClasses(@Param("semesterId") Long semesterId);
    @Select("<script>SELECT tc.id, tc.class_code, c.id AS course_id, c.name AS course_name, c.credits,"
            + " tc.semester_id, tc.capacity, tc.status, u.enabled AS teacher_enabled"
            + " FROM teaching_class tc JOIN course c ON c.id=tc.course_id"
            + " JOIN teacher t ON t.id=tc.teacher_id JOIN sys_user u ON u.id=t.user_id"
            + " WHERE tc.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    // 批量查询选课提交涉及的教学班和判定选课资格所需的字段。
    List<SelectionClass> selectionClasses(@Param("ids") List<Long> ids);
    // 返回属于指定教师的教学班，可按学期限定。
    @Select(AcademicSqlProvider.CLASSES + " WHERE t.id=#{teacherId} AND (#{semesterId} IS NULL OR s.id=#{semesterId}) ORDER BY tc.id DESC")
    List<ClassRow> teacherClasses(@Param("teacherId") Long teacherId, @Param("semesterId") Long semesterId);
    // 查出单个教学班的全部上课时段。
    @Select("SELECT id, teaching_class_id, weekday, start_period, end_period, start_week, end_week, classroom FROM course_schedule WHERE teaching_class_id=#{id} ORDER BY weekday, start_period, start_week, id")
    List<Schedule> schedules(Long id);
    @Select("<script>SELECT id, teaching_class_id, weekday, start_period, end_period, start_week, end_week, classroom"
            + " FROM course_schedule WHERE teaching_class_id IN"
            + " <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY teaching_class_id, weekday, start_period, start_week, id</script>")
    // 一次查出一组教学班的上课时段，供服务按班级归类。
    List<Schedule> schedulesOf(@Param("ids") Collection<Long> ids);
    @Select("""
            SELECT sc.teaching_class_id, tc.teacher_id, tc.class_code, sc.weekday,
                   sc.start_period, sc.end_period, sc.start_week, sc.end_week, sc.classroom
            FROM course_schedule sc JOIN teaching_class tc ON tc.id=sc.teaching_class_id
            WHERE tc.semester_id=#{semesterId} AND tc.status='PUBLISHED' AND tc.id<>#{excludedId}
            """)
    // 读取同学期其他教学班已占用的教师和教室时段，供排课校验。
    List<ResourceSlot> resourceSlots(@Param("semesterId") Long semesterId, @Param("excludedId") Long excludedId);

    // 在当前事务内锁定学期，协调选课、排课和成绩更新。
    // SQL Server 和其他数据库使用各自的行锁语法。服务层用同一加锁顺序协调选课、排课与成绩操作。
    @Select("SELECT id FROM semester WHERE id=#{id} FOR UPDATE")
    @Select(value="SELECT id FROM semester WITH (UPDLOCK, ROWLOCK) WHERE id=#{id}", databaseId="sqlserver") Long lockSemester(Long id);
    // 在当前事务内锁定学生档案，协调同一学生同时提交的操作。
    @Select("SELECT id FROM student WHERE id=#{id} FOR UPDATE")
    @Select(value="SELECT id FROM student WITH (UPDLOCK, ROWLOCK) WHERE id=#{id}", databaseId="sqlserver") Long lockStudent(Long id);
    // 在当前事务内锁定教学班，保护容量与选课相关的更新。
    @Select("SELECT id FROM teaching_class WHERE id=#{id} FOR UPDATE")
    @Select(value="SELECT id FROM teaching_class WITH (UPDLOCK, ROWLOCK) WHERE id=#{id}", databaseId="sqlserver") Long lockClass(Long id);

    // 统计指定班级仍在选课的学生人数。
    @Select("SELECT COUNT(*) FROM enrollment WHERE teaching_class_id=#{id} AND status='ENROLLED'") long selectedCount(Long id);
    @Select("<script>SELECT teaching_class_id, COUNT(*) AS total FROM enrollment WHERE status='ENROLLED'"
            + " AND teaching_class_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " GROUP BY teaching_class_id</script>")
    // 对一组教学班分别统计有效选课人数。
    List<ClassCount> selectedCounts(@Param("ids") List<Long> ids);
    // 统计教学班全部选退课记录，用于保护历史数据。
    @Select("SELECT COUNT(*) FROM enrollment WHERE teaching_class_id=#{id}") long enrollmentHistory(Long id);
    // 统计引用该课程的教学班，删除课程前据此检查。
    @Select("SELECT COUNT(*) FROM teaching_class WHERE course_id=#{id}") long courseReferences(Long id);
    // 统计该学期的教学班，删除学期前据此检查。
    @Select("SELECT COUNT(*) FROM teaching_class WHERE semester_id=#{id}") long semesterReferences(Long id);
    // 列出该课程出现过的学期，更新课程时按这些学期加锁。
    @Select("SELECT DISTINCT semester_id FROM teaching_class WHERE course_id=#{id} ORDER BY semester_id") List<Long> courseSemesters(Long id);
    // 逐个累加学生在该学期的有效选课学分，取其中的最大值供学分上限检查。
    @Select("""
            SELECT COALESCE(MAX(t.credits),0) FROM (
                SELECT SUM(c.credits) AS credits FROM enrollment e
                JOIN teaching_class tc ON tc.id=e.teaching_class_id JOIN course c ON c.id=tc.course_id
                WHERE tc.semester_id=#{id} AND e.status='ENROLLED' GROUP BY e.student_id
            ) t
            """) java.math.BigDecimal highestSelectedCredits(Long id);

    // 将学号与学籍信息写入学生档案并关联新账号。
    @Insert("INSERT INTO student(user_id,student_no,major,class_name) VALUES(#{userId},#{body.studentNo},#{body.major},#{body.className})")
    int insertStudent(@Param("userId") Long userId, @Param("body") StudentRequest body);
    // 更新指定学生的学籍字段。
    @Update("UPDATE student SET major=#{body.major},class_name=#{body.className} WHERE id=#{id}")
    int updateStudent(@Param("id") Long id, @Param("body") StudentRequest body);
    // 将工号和院系写入教师档案并关联新账号。
    @Insert("INSERT INTO teacher(user_id,teacher_no,department) VALUES(#{userId},#{body.teacherNo},#{body.department})")
    int insertTeacher(@Param("userId") Long userId, @Param("body") TeacherRequest body);
    // 更新指定教师的任职字段。
    @Update("UPDATE teacher SET department=#{body.department} WHERE id=#{id}")
    int updateTeacher(@Param("id") Long id, @Param("body") TeacherRequest body);
    // 同步修改账号的显示姓名和启用状态。
    @Update("UPDATE sys_user SET display_name=#{name},enabled=#{enabled} WHERE id=#{id}")
    int updatePersonAccount(@Param("id") Long id, @Param("name") String name, @Param("enabled") boolean enabled);
    // 把新密码哈希写回账号记录。
    @Update("UPDATE sys_user SET password_hash=#{hash} WHERE id=#{id}")
    int updatePassword(@Param("id") Long id, @Param("hash") String hash);

    // 将课程编号和教学基础信息写入课程表。
    @Insert("INSERT INTO course(course_code,name,credits,hours,description) VALUES(#{courseCode},#{name},#{credits},#{hours},#{description})") int insertCourse(CourseRequest body);
    // 用唯一课程编号查回对应记录。
    @Select(AcademicSqlProvider.COURSES + " WHERE course_code=#{code}") Course courseByCode(String code);
    // 按课程主键更新课程信息。
    @Update("UPDATE course SET course_code=#{body.courseCode},name=#{body.name},credits=#{body.credits},hours=#{body.hours},description=#{body.description} WHERE id=#{id}")
    int updateCourse(@Param("id") Long id, @Param("body") CourseRequest body);
    // 删除指定课程记录，其引用关系由服务先检查。
    @Delete("DELETE FROM course WHERE id=#{id}") int deleteCourse(Long id);
    // 将日期、选课时间和学分限制写入学期表。
    @Insert("INSERT INTO semester(name,start_date,end_date,selection_start,selection_end,max_credits) VALUES(#{name},#{startDate},#{endDate},#{selectionStart},#{selectionEnd},#{maxCredits})") int insertSemester(SemesterRequest body);
    // 用学期名称查回对应记录。
    @Select(AcademicSqlProvider.SEMESTERS + " WHERE name=#{name}") Semester semesterByName(String name);
    // 按学期主键更新日期、选课窗口和学分上限。
    @Update("UPDATE semester SET name=#{body.name},start_date=#{body.startDate},end_date=#{body.endDate},selection_start=#{body.selectionStart},selection_end=#{body.selectionEnd},max_credits=#{body.maxCredits} WHERE id=#{id}")
    int updateSemester(@Param("id") Long id, @Param("body") SemesterRequest body);
    // 删除指定学期记录，其引用关系由服务先检查。
    @Delete("DELETE FROM semester WHERE id=#{id}") int deleteSemester(Long id);

    // 在教学班表新增一条具体开课记录。
    @Insert("INSERT INTO teaching_class(class_code,course_id,teacher_id,semester_id,capacity,status) VALUES(#{classCode},#{courseId},#{teacherId},#{semesterId},#{capacity},#{status})") int insertClass(ClassRequest body);
    // 通过班级编码查回保存后的教学班。
    @Select(AcademicSqlProvider.CLASSES + " WHERE tc.class_code=#{code}") ClassRow classByCode(String code);
    // 写入教学班的教师、容量和发布状态等字段。
    @Update("UPDATE teaching_class SET class_code=#{body.classCode},course_id=#{body.courseId},teacher_id=#{body.teacherId},semester_id=#{body.semesterId},capacity=#{body.capacity},status=#{body.status},version=version+1 WHERE id=#{id}")
    int updateClass(@Param("id") Long id, @Param("body") ClassRequest body);
    // 清除指定教学班的排课，供替换或删除教学班使用。
    @Delete("DELETE FROM course_schedule WHERE teaching_class_id=#{id}") int deleteSchedules(Long id);
    // 将一段上课时间和教室写入指定教学班。
    @Insert("INSERT INTO course_schedule(teaching_class_id,weekday,start_period,end_period,start_week,end_week,classroom) VALUES(#{id},#{body.weekday},#{body.startPeriod},#{body.endPeriod},#{body.startWeek},#{body.endWeek},#{body.classroom})")
    int insertSchedule(@Param("id") Long id, @Param("body") ScheduleRequest body);
    // 删除指定教学班，历史引用由服务层检查。
    @Delete("DELETE FROM teaching_class WHERE id=#{id}") int deleteClass(Long id);

    // 按记录编号查出选课归属及教学班信息。
    @Select(AcademicSqlProvider.ENROLLMENTS + " WHERE e.id=#{id}") Enrollment enrollment(Long id);
    // 读取指定学生的选退课历史，可限定学期。
    @Select(AcademicSqlProvider.ENROLLMENTS + " WHERE st.id=#{studentId} AND (#{semesterId} IS NULL OR se.id=#{semesterId}) ORDER BY e.selected_at DESC, e.id DESC")
    List<Enrollment> studentEnrollments(@Param("studentId") Long studentId, @Param("semesterId") Long semesterId);
    @Select("<script>SELECT e.teaching_class_id, tc.class_code, c.id AS course_id, c.name AS course_name, c.credits, tc.semester_id"
            + " FROM enrollment e JOIN teaching_class tc ON tc.id=e.teaching_class_id JOIN course c ON c.id=tc.course_id"
            + " WHERE e.student_id=#{studentId} AND e.status='ENROLLED' AND tc.semester_id IN"
            + " <foreach collection='semesterIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY e.selected_at DESC, e.id DESC</script>")
    // 查询指定学生在相关学期仍有效的选课，用于重复、学分和时间校验。
    List<ActiveSelection> activeSelections(@Param("studentId") Long studentId, @Param("semesterIds") List<Long> semesterIds);
    @Select("<script>SELECT teaching_class_id, id AS enrollment_id FROM enrollment WHERE student_id=#{studentId}"
            + " AND teaching_class_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    // 找出本次要选的教学班中已有的历史记录。
    List<ClassEnrollmentId> existingEnrollments(@Param("studentId") Long studentId, @Param("ids") List<Long> ids);
    @Select("<script>" + AcademicSqlProvider.ENROLLMENTS + " WHERE e.student_id=#{studentId} AND e.teaching_class_id IN"
            + " <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    // 批量查回此次选择成功的记录，用于构造响应。
    List<Enrollment> selectedEnrollments(@Param("studentId") Long studentId, @Param("ids") List<Long> ids);
    // 检查学生与指定教学班之间是否已有选课记录。
    @Select("SELECT id FROM enrollment WHERE student_id=#{studentId} AND teaching_class_id=#{classId}")
    Long existingEnrollment(@Param("studentId") Long studentId, @Param("classId") Long classId);
    // 首次选课时写入学生、教学班和选课时间。
    @Insert("INSERT INTO enrollment(student_id,teaching_class_id,status,selected_at) VALUES(#{studentId},#{classId},'ENROLLED',#{now})")
    int insertEnrollment(@Param("studentId") Long studentId, @Param("classId") Long classId, @Param("now") LocalDateTime now);
    // 把退课过的记录改回已选，并刷新选择时间。
    @Update("UPDATE enrollment SET status='ENROLLED',selected_at=#{now},dropped_at=NULL WHERE id=#{id}")
    int reactivateEnrollment(@Param("id") Long id, @Param("now") LocalDateTime now);
    // 改为已退状态并记录退课时间。
    // 数据表中的学生与教学班组合必须唯一，保留原记录便于之后恢复选课和查询历史。
    @Update("UPDATE enrollment SET status='DROPPED',dropped_at=#{now} WHERE id=#{id}")
    int dropEnrollment(@Param("id") Long id, @Param("now") LocalDateTime now);
    // 查询当前仍在该教学班选课的学生名单。
    @Select("""
            SELECT st.id, st.student_no, u.display_name, st.major, st.class_name, e.selected_at
            FROM enrollment e JOIN student st ON st.id=e.student_id JOIN sys_user u ON u.id=st.user_id
            WHERE e.teaching_class_id=#{id} AND e.status='ENROLLED' ORDER BY st.student_no
            """) List<RosterStudent> roster(Long id);
}
