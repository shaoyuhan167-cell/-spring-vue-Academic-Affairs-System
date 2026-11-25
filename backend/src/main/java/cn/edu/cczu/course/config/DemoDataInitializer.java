package cn.edu.cczu.course.config;

import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.module.user.model.UserAccount;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 显式启用时初始化样例账号和课程；重复启动不会覆盖已有账号或课程。 */
@Component
@Profile({"sqlserver", "test"})
@ConditionalOnProperty(name = "app.sample-data.enabled", havingValue = "true")
@Order(0)
public class DemoDataInitializer implements ApplicationRunner {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final String samplePassword;

    public DemoDataInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder, JdbcTemplate jdbcTemplate,
                               @Value("${app.sample-data.password:}") String samplePassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.samplePassword = samplePassword;
    }

    // 只在启用样例数据时补齐演示账号和课程，便于体验不同角色。
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (samplePassword.length() < 10 || samplePassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("启用样例数据时必须设置 10 个字符以上且不超过 72 字节的账号初始密码");
        }
        String hash = passwordEncoder.encode(samplePassword);
        seedUser("admin", hash, "系统管理员", "ADMIN");
        seedUser("20260001", hash, "演示学生", "STUDENT");
        seedUser("T2026001", hash, "演示教师", "TEACHER");
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM student WHERE student_no = '20260001'", Long.class) == 0) {
            jdbcTemplate.update("INSERT INTO student (user_id, student_no, major, class_name) VALUES (?, ?, ?, ?)",
                    userMapper.findByUsername("20260001").id(), "20260001", "计算机科学与技术", "计算机 2301");
        }
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teacher WHERE teacher_no = 'T2026001'", Long.class) == 0) {
            jdbcTemplate.update("INSERT INTO teacher (user_id, teacher_no, department) VALUES (?, ?, ?)",
                    userMapper.findByUsername("T2026001").id(), "T2026001", "计算机学院");
        }
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM semester WHERE name = ?", Long.class,
                "2026—2027 学年第一学期") == 0) {
            jdbcTemplate.update("""
                INSERT INTO semester (name, start_date, end_date, selection_start, selection_end, max_credits)
                VALUES ('2026—2027 学年第一学期', '2026-09-01', '2027-01-31',
                        '2026-11-09 08:00:00', '2026-11-28 23:59:59', 30.0)
                """);
        }
        seedCourse("CS001", "Web 应用开发", 3.0, 48, "Spring Boot 与 Web 系统设计基础", "A-301", 1);
        seedCourse("CS002", "数据库系统原理", 3.5, 56, "关系模型、SQL 与数据库设计", "B-205", 3);
        seedCourse("CS003", "软件工程", 2.0, 32, "软件需求、设计与项目协作", "A-402", 5);
    }

    // 按课程编号补充演示课程和相关开课记录，重复启动时保留已有数据。
    private void seedCourse(String code, String name, double credits, int hours,
                            String description, String classroom, int weekday) {
        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM course WHERE course_code = ?", Long.class, code) != 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO course (course_code, name, credits, hours, description) VALUES (?, ?, ?, ?, ?)",
                code, name, credits, hours, description);
        Long courseId = jdbcTemplate.queryForObject("SELECT id FROM course WHERE course_code = ?", Long.class, code);
        jdbcTemplate.update("""
                INSERT INTO teaching_class (class_code, course_id, teacher_id, semester_id, capacity, status)
                SELECT ?, ?, t.id, s.id, 60, 'PUBLISHED'
                FROM teacher t CROSS JOIN semester s WHERE t.teacher_no = 'T2026001'
                AND s.name = '2026—2027 学年第一学期'
                """, code + "-2026-01", courseId);
        Long classId = jdbcTemplate.queryForObject("SELECT id FROM teaching_class WHERE class_code = ?",
                Long.class, code + "-2026-01");
        jdbcTemplate.update("""
                INSERT INTO course_schedule
                (teaching_class_id, weekday, start_period, end_period, start_week, end_week, classroom)
                VALUES (?, ?, 1, 2, 1, 16, ?)
                """, classId, weekday, classroom);
    }

    // 账号不存在时才加入演示账号，保留已有的身份信息。
    // 启动时先按用户名查找已有记录。只有账号缺失才使用演示配置新增，重复启动不会覆盖原密码。
    private void seedUser(String username, String hash, String name, String role) {
        if (userMapper.findByUsername(username) == null) {
            userMapper.insert(new UserAccount(null, username, hash, name, role, true));
        }
    }
}
