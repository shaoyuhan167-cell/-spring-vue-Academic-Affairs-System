package cn.edu.cczu.course.module.academic.mapper;

import java.util.Map;

/** 分页 SQL 只拼接固定字段与语法，所有用户输入都通过绑定参数传递。 */
public class AcademicSqlProvider {
    public static final String STUDENTS = """
            SELECT s.id, s.user_id, s.student_no, u.display_name, s.major, s.class_name, u.enabled
            FROM student s JOIN sys_user u ON u.id=s.user_id
            """;
    public static final String TEACHERS = """
            SELECT t.id, t.user_id, t.teacher_no, u.display_name, t.department, u.enabled
            FROM teacher t JOIN sys_user u ON u.id=t.user_id
            """;
    public static final String COURSES = "SELECT id, course_code, name, credits, hours, description FROM course";
    public static final String SEMESTERS = """
            SELECT id, name, start_date, end_date, selection_start, selection_end, max_credits FROM semester
            """;
    public static final String CLASSES = """
            SELECT tc.id, tc.class_code, c.id AS course_id, c.course_code, c.name AS course_name,
                   c.credits, c.hours, t.id AS teacher_id, u.display_name AS teacher_name,
                   s.id AS semester_id, s.name AS semester_name, tc.capacity, tc.status,
                   (SELECT COUNT(*) FROM enrollment e WHERE e.teaching_class_id=tc.id AND e.status='ENROLLED') AS selected_count
            FROM teaching_class tc JOIN course c ON c.id=tc.course_id
            JOIN teacher t ON t.id=tc.teacher_id JOIN sys_user u ON u.id=t.user_id
            JOIN semester s ON s.id=tc.semester_id
            """;
    public static final String ENROLLMENTS = """
            SELECT e.id, st.id AS student_id, st.student_no, su.display_name AS student_name,
                   tc.id AS teaching_class_id, tc.class_code, c.id AS course_id, c.name AS course_name,
                   c.credits, tu.display_name AS teacher_name, se.id AS semester_id, se.name AS semester_name,
                   e.status, e.selected_at, e.dropped_at
            FROM enrollment e JOIN student st ON st.id=e.student_id JOIN sys_user su ON su.id=st.user_id
            JOIN teaching_class tc ON tc.id=e.teaching_class_id JOIN course c ON c.id=tc.course_id
            JOIN teacher t ON t.id=tc.teacher_id JOIN sys_user tu ON tu.id=t.user_id
            JOIN semester se ON se.id=tc.semester_id
            """;

    // 按关键词和页码读取学生档案，供管理列表显示。
    public String students(Map<String, Object> params) { return page(studentQuery(params), "s.id"); }
    // 统计符合当前条件的学生总数，用于计算分页。
    public String countStudents(Map<String, Object> params) { return count(studentQuery(params)); }
    // 按关键词和页码读取教师档案，供管理列表显示。
    public String teachers(Map<String, Object> params) { return page(teacherQuery(params), "t.id"); }
    // 统计符合当前条件的教师总数，用于计算分页。
    public String countTeachers(Map<String, Object> params) { return count(teacherQuery(params)); }
    // 按编号或课程名筛选课程库，并返回当前页。
    public String courses(Map<String, Object> params) { return page(courseQuery(params), "id"); }
    // 统计符合当前条件的课程总数，用于计算分页。
    public String countCourses(Map<String, Object> params) { return count(courseQuery(params)); }
    // 查询学期。管理列表支持分页和关键词筛选。
    public String semesters(Map<String, Object> params) { return page(semesterQuery(params), "start_date DESC, id"); }
    // 统计符合当前条件的学期总数，用于计算分页。
    public String countSemesters(Map<String, Object> params) { return count(semesterQuery(params)); }
    // 按条件查询教学班，供列表或下拉框使用。
    public String classes(Map<String, Object> params) { return page(classQuery(params), "tc.id"); }
    // 统计符合当前条件的教学班总数，用于计算分页。
    public String countClasses(Map<String, Object> params) { return count(classQuery(params)); }
    // 按关键词、学期和选退课状态查询后台选课记录。
    public String enrollments(Map<String, Object> params) { return page(enrollmentQuery(params), "e.id"); }
    // 统计符合当前条件的选课记录总数，用于计算分页。
    public String countEnrollments(Map<String, Object> params) { return count(enrollmentQuery(params)); }

    // 在学生基础查询上增加学号、姓名、专业和班级搜索条件。
    private String studentQuery(Map<String, Object> params) { return STUDENTS + search(params, "s.student_no", "u.display_name", "s.major", "s.class_name"); }
    // 在教师基础查询上增加工号、姓名和院系搜索条件。
    private String teacherQuery(Map<String, Object> params) { return TEACHERS + search(params, "t.teacher_no", "u.display_name", "t.department"); }
    // 在课程基础查询上增加编号和名称搜索条件。
    private String courseQuery(Map<String, Object> params) { return COURSES + search(params, "course_code", "name"); }
    // 按学期名称构造查询条件。
    private String semesterQuery(Map<String, Object> params) { return SEMESTERS + search(params, "name"); }
    // 为开课管理查询叠加关键词与学期条件。
    private String classQuery(Map<String, Object> params) {
        return CLASSES + search(params, "tc.class_code", "c.course_code", "c.name", "u.display_name")
                + (params.get("semesterId") == null ? "" : " AND tc.semester_id=#{semesterId}");
    }
    // 为后台选课记录叠加关键词、学期与状态条件。
    private String enrollmentQuery(Map<String, Object> params) {
        return ENROLLMENTS + search(params, "st.student_no", "su.display_name", "tc.class_code", "c.name")
                + (params.get("semesterId") == null ? "" : " AND tc.semester_id=#{semesterId}")
                + (params.get("status") == null ? "" : " AND e.status=#{status}");
    }
    // 只在有搜索词时追加模糊匹配，参数值仍交给 MyBatis 绑定。
    private String search(Map<String, Object> params, String... columns) {
        // 保留统一的 WHERE 起点，有学期或状态条件时可以直接继续追加 AND。
        if (params.get("pattern") == null) return " WHERE 1=1";
        return " WHERE (" + String.join(" OR ", java.util.Arrays.stream(columns)
                .map(column -> column + " LIKE #{pattern} ESCAPE '~'").toList()) + ")";
    }
    // 包装同一条过滤查询，计算列表的总记录数。
    private String count(String query) { return "SELECT COUNT(*) FROM (" + query + ") counted_rows"; }
    // 按指定字段排序后分页。
    private String page(String query, String order) {
        return query + " ORDER BY " + order + " DESC OFFSET #{offset} ROWS FETCH NEXT #{size} ROWS ONLY";
    }
}
