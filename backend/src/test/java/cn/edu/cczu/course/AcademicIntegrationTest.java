package cn.edu.cczu.course;

import cn.edu.cczu.course.common.exception.BusinessException;
import cn.edu.cczu.course.config.DemoDataInitializer;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.*;
import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import cn.edu.cczu.course.module.academic.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用独立内存库测试真实事务，避免修改笔记本部署库的数据。 */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:academic_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AcademicIntegrationTest {
    @Autowired ManagementService management;
    @Autowired EnrollmentService enrollment;
    @Autowired TeachingService teaching;
    @Autowired AcademicMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoDataInitializer samples;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    Semester semester;
    Teacher teacher;
    Student student;
    private static final String PASSWORD = "Test123456!";

    // 每个用例开始前准备独立的学期、账号与选课数据。
    @BeforeEach
    void prepareIsolatedData() {
        for (String table : List.of("audit_log","announcement_read","student_grade","user_profile","announcement","enrollment","course_schedule","teaching_class","semester","course","student","teacher","sys_user")) {
            jdbc.update("DELETE FROM " + table);
        }
        samples.run(new DefaultApplicationArguments());
        // 构造覆盖当前时间的选课窗口，测试可提交选课而无需依赖固定校历。
        var now = LocalDateTime.now();
        semester = management.createSemester(new SemesterRequest("业务测试学期", LocalDate.now().minusDays(30), LocalDate.now().plusDays(100), now.minusDays(1), now.plusDays(1), new BigDecimal("10.0")));
        teacher = management.createTeacher(new TeacherRequest("TEST_T", "测试教师", "计算机学院", true, PASSWORD));
        student = management.createStudent(new StudentRequest("TEST_S", "测试学生", "计算机", "测试一班", true, PASSWORD));
    }

    // 创建测试课程，供教学班和选课场景复用。
    Course course(String code, String credits) {
        return management.createCourse(new CourseRequest(code, "测试课程 " + code, new BigDecimal(credits), 32, "业务测试"));
    }
    // 准备一个在测试学期内已发布的教学班及排课。
    ClassView openClass(Course course, Teacher owner, int capacity, int day, int first, int last, String room) {
        return management.createClass(new ClassRequest("TC_" + UUID.randomUUID().toString().substring(0,8), course.id(), owner.id(), semester.id(), capacity, "PUBLISHED", List.of(new ScheduleRequest(day,first,last,1,16,room))));
    }
    // 从现有教学班构造可用于更新的表单。
    ClassRequest edit(ClassView row, int capacity, String status) {
        return new ClassRequest(row.classCode(),row.courseId(),row.teacherId(),row.semesterId(),capacity,status,
                row.schedules().stream().map(s -> new ScheduleRequest(s.weekday(),s.startPeriod(),s.endPeriod(),s.startWeek(),s.endWeek(),s.classroom())).toList());
    }

    // 验证后台分页结果以及已被引用的数据不能被删除。
    @Test
    void managementSupportsPaginationAndProtectsReferencedData() {
        var result = management.students(1,10,"测试学生");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records().getFirst().studentNo()).isEqualTo("TEST_S");
        var updated = management.updateStudent(student.id(), new StudentRequest("TEST_S","已修改学生","软件工程","测试二班",false,null));
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.className()).isEqualTo("测试二班");
        assertThatThrownBy(() -> management.students(0,10,null)).isInstanceOf(BusinessException.class);
        var c = course("CRUD_C","2.0");
        var offered = openClass(c,teacher,10,1,1,2,"测试教室 A");
        assertThat(management.classes(1,10,"CRUD_C",semester.id()).total()).isEqualTo(1);
        assertThatThrownBy(() -> management.deleteCourse(c.id())).hasMessageContaining("已有教学班");
        management.deleteClass(offered.id());
        management.deleteCourse(c.id());
        assertThat(mapper.course(c.id())).isNull();
    }

    // 验证账号状态变化、删除教学班和退课都会留下审计记录。
    @Test
    void auditsAccountChangesClassDeletionAndDrop() throws Exception {
        var admin = login("admin", "Demo123456!");
        var studentSession = login("TEST_S", PASSWORD);
        var offered = openClass(course("AUDIT_C", "2.0"), teacher, 30, 1, 1, 2, "A");
        var unused = openClass(course("AUDIT_UNUSED", "2.0"), teacher, 30, 2, 1, 2, "B");
        var selected = enrollment.enroll("TEST_S", offered.id()).info().id();
        mvc.perform(delete("/api/enrollments/{id}", selected).session(studentSession).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/classes/{id}", unused.id()).session(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(put("/api/admin/teachers/{id}", teacher.id()).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                new TeacherRequest(teacher.teacherNo(), teacher.displayName(), teacher.department(), false, null))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/students/{id}/password", student.id()).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Another12345!\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action FROM audit_log ORDER BY id", String.class))
                .containsExactly("ENROLL_DROP", "CLASS_DELETE", "ACCOUNT_UPDATE", "PASSWORD_RESET");
    }

    // 验证目录既支持按学期筛选，也能返回没有限定学期的结果。
    @Test
    void catalogFiltersBySemesterAndSupportsUnfilteredRequests() throws Exception {
        var first = openClass(course("CATALOG_A", "2.0"), teacher, 30, 1, 1, 2, "A");
        var other = management.createSemester(new SemesterRequest("另一学期", semester.startDate(),
                semester.endDate(), semester.selectionStart(), semester.selectionEnd(), semester.maxCredits()));
        var second = management.createClass(new ClassRequest("CATALOG_B", first.courseId(), teacher.id(),
                other.id(), 30, "PUBLISHED", List.of(new ScheduleRequest(1, 1, 2, 1, 16, "B"))));
        var filtered = enrollment.catalog(semester.id());
        assertThat(filtered).extracting(ClassView::id).containsExactly(first.id());
        assertThat(filtered.getFirst().schedules()).containsExactlyElementsOf(first.schedules());
        assertThat(enrollment.catalog(null)).extracting(ClassView::id).contains(first.id(), second.id());
        mvc.perform(get("/api/courses").param("semesterId", other.id().toString()).with(user("TEST_S").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(second.id()));
        mvc.perform(get("/api/courses").param("semesterId", "999999").with(user("TEST_S").roles("STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    // 验证批量读取排课后，原有班级顺序和空排课班级仍被保留。
    @Test
    void batchedSchedulesKeepOrderAndIncludeUnscheduledClasses() {
        var scheduled = management.createClass(new ClassRequest("SCHEDULE_ORDER", course("SCHEDULE_C", "2.0").id(),
                teacher.id(), semester.id(), 30, "PUBLISHED",
                List.of(new ScheduleRequest(3, 5, 6, 1, 16, "B"), new ScheduleRequest(1, 1, 2, 1, 16, "A"))));
        var draft = management.createClass(new ClassRequest("SCHEDULE_DRAFT", scheduled.courseId(),
                teacher.id(), semester.id(), 30, "DRAFT", List.of()));
        var classes = management.classes(1, 10, "SCHEDULE_", semester.id()).records();
        assertThat(classes).filteredOn(row -> row.id().equals(scheduled.id())).singleElement()
                .satisfies(row -> assertThat(row.schedules()).containsExactlyElementsOf(scheduled.schedules()));
        assertThat(classes).filteredOn(row -> row.id().equals(draft.id())).singleElement()
                .satisfies(row -> assertThat(row.schedules()).isEmpty());
    }

    // 验证输入百分号等字符时，搜索按字面匹配。
    @Test
    void literalWildcardSearchDoesNotMatchEveryCourse() {
        var c = management.createCourse(new CourseRequest("WILD_C","百分之%课程",new BigDecimal("1.0"),16,""));
        assertThat(management.courses(1,10,"%").records()).extracting(Course::id).containsExactly(c.id());
    }

    // 验证选课、退课再选后保留同一记录，并正确生成课表。
    @Test
    void enrollmentDropAndReEnrollmentKeepHistoryAndBuildTimetable() {
        var offered = openClass(course("FLOW_C","3.0"),teacher,2,2,3,4,"测试教室 B");
        var first = enrollment.enroll("TEST_S",offered.id());
        assertThat(mapper.selectedCount(offered.id())).isEqualTo(1);
        assertThat(enrollment.timetable("TEST_S",semester.id(),5)).hasSize(1);
        assertThat(enrollment.timetable("TEST_S",semester.id(),20)).isEmpty();
        assertThat(teaching.roster("TEST_T",false,offered.id())).extracting(RosterStudent::studentNo).containsExactly("TEST_S");
        assertThatThrownBy(() -> enrollment.enroll("TEST_S",offered.id())).hasMessageContaining("不能重复");
        enrollment.drop("TEST_S",first.info().id());
        assertThat(mapper.selectedCount(offered.id())).isZero();
        assertThat(enrollment.timetable("TEST_S",semester.id(),null)).isEmpty();
        assertThat(mapper.enrollment(first.info().id()).status()).isEqualTo("DROPPED");
        var again = enrollment.enroll("TEST_S",offered.id());
        assertThat(again.info().id()).isEqualTo(first.info().id());
        assertThat(mapper.enrollmentHistory(offered.id())).isEqualTo(1);
        assertThatThrownBy(() -> management.deleteClass(offered.id())).hasMessageContaining("历史");
    }

    // 覆盖尚未开放选课和教学班未发布两种拒绝场景。
    @Test
    void selectionWindowAndUnpublishedStatusAreEnforced() {
        var offered = openClass(course("WINDOW_C","2.0"),teacher,10,3,1,2,"测试教室 C");
        management.updateClass(offered.id(),edit(offered,10,"CLOSED"));
        assertThatThrownBy(() -> enrollment.enroll("TEST_S",offered.id())).hasMessageContaining("关闭");
        management.updateClass(offered.id(),edit(offered,10,"PUBLISHED"));
        management.updateSemester(semester.id(),new SemesterRequest(semester.name(),semester.startDate(),semester.endDate(),LocalDateTime.now().minusDays(2),LocalDateTime.now().minusDays(1),semester.maxCredits()));
        assertThatThrownBy(() -> enrollment.enroll("TEST_S",offered.id())).hasMessageContaining("时间范围");
    }

    // 让两名学生同时争抢最后一个名额，验证只有一个人成功。
    @Test
    void lastSeatCannotBeTakenByTwoStudentsConcurrently() throws Exception {
        management.createStudent(new StudentRequest("TEST_S2","另一学生","计算机","测试一班",true,PASSWORD));
        var offered = openClass(course("CAP_C","2.0"),teacher,1,4,1,2,"测试教室 D");
        assertThat(race(() -> enrollment.enroll("TEST_S",offered.id()), () -> enrollment.enroll("TEST_S2",offered.id()))).isEqualTo(1);
        assertThat(mapper.selectedCount(offered.id())).isEqualTo(1);
    }

    // 让同一学生并发提交冲突的课程，验证只能选中其中一门。
    @Test
    void concurrentChoicesForOneStudentCannotBypassTimeConflict() throws Exception {
        var otherTeacher = management.createTeacher(new TeacherRequest("TEST_T2","另一教师","计算机学院",true,PASSWORD));
        var first = openClass(course("TIME_C1","2.0"),teacher,10,5,1,2,"测试教室 E");
        var second = openClass(course("TIME_C2","2.0"),otherTeacher,10,5,2,3,"测试教室 F");
        assertThat(race(() -> enrollment.enroll("TEST_S",first.id()), () -> enrollment.enroll("TEST_S",second.id()))).isEqualTo(1);
        assertThat(enrollment.mine("TEST_S",semester.id())).hasSize(1);
    }

    // 验证冲突提示点出双方课程，并且前面已经处理的选择也被回滚。
    @Test
    void batchConflictReportsBothClassesAndRollsBackEarlierChoices() throws Exception {
        var otherTeacher = management.createTeacher(new TeacherRequest("TEST_T2", "另一教师", "计算机学院", true, PASSWORD));
        var first = openClass(course("BATCH_A", "2.0"), teacher, 10, 5, 1, 2, "测试教室 A");
        var conflict = openClass(course("BATCH_B", "2.0"), otherTeacher, 10, 5, 2, 3, "测试教室 B");
        var unrelated = openClass(course("BATCH_C", "2.0"), teacher, 10, 6, 1, 2, "测试教室 A");
        // 从实际 HTTP 入口提交批次，后续检查冲突提示与事务回滚后的状态。
        var session = login("TEST_S", PASSWORD);

        mvc.perform(post("/api/enrollments/batch").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new BatchEnrollRequest(List.of(first.id(), conflict.id())))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(first.classCode()),
                        org.hamcrest.Matchers.containsString(conflict.classCode()),
                        org.hamcrest.Matchers.containsString("本次待选课程"),
                        org.hamcrest.Matchers.containsString("周五 第2节，第1～16周"))));
        assertThat(enrollment.mine("TEST_S", semester.id())).isEmpty();

        enrollment.enroll("TEST_S", first.id());
        assertThatThrownBy(() -> enrollment.enrollBatch("TEST_S", List.of(unrelated.id(), conflict.id())))
                .hasMessageContaining("已选课程");
        assertThat(mapper.enrollmentHistory(unrelated.id())).isZero();
        assertThat(enrollment.mine("TEST_S", semester.id())).extracting(view -> view.info().teachingClassId())
                .containsExactly(first.id());
    }

    // 验证批次内不能重复选择同一课程，也不能累计超过学分限制。
    @Test
    void batchRejectsDuplicateCourseAndCumulativeCreditOverflow() {
        var repeated = course("BATCH_REPEAT", "6.0");
        var first = openClass(repeated, teacher, 30, 1, 1, 2, "A");
        var duplicate = openClass(repeated, teacher, 30, 2, 1, 2, "B");
        var extra = openClass(course("BATCH_CREDITS", "4.1"), teacher, 30, 3, 1, 2, "C");
        assertThatThrownBy(() -> enrollment.enrollBatch("TEST_S", List.of(first.id(), duplicate.id())))
                .hasMessageContaining("不能重复选择教学班");
        assertThat(enrollment.mine("TEST_S", semester.id())).isEmpty();
        assertThatThrownBy(() -> enrollment.enrollBatch("TEST_S", List.of(first.id(), extra.id())))
                .hasMessageContaining("学分上限");
        assertThat(enrollment.mine("TEST_S", semester.id())).isEmpty();
    }

    // 验证同一天同节次但上课周完全错开时可以一起选择。
    @Test
    void batchAcceptsSamePeriodsInNonOverlappingWeeks() throws Exception {
        var first = management.createClass(new ClassRequest("BATCH_EARLY", course("EARLY_C", "2.0").id(), teacher.id(),
                semester.id(), 10, "PUBLISHED", List.of(new ScheduleRequest(2, 1, 2, 1, 8, "测试教室"))));
        var second = management.createClass(new ClassRequest("BATCH_LATE", course("LATE_C", "2.0").id(), teacher.id(),
                semester.id(), 10, "PUBLISHED", List.of(new ScheduleRequest(2, 1, 2, 9, 16, "测试教室"))));
        mvc.perform(post("/api/enrollments/batch").session(login("TEST_S", PASSWORD)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new BatchEnrollRequest(List.of(second.id(), first.id())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].info.teachingClassId").value(second.id()))
                .andExpect(jsonPath("$.data[1].info.teachingClassId").value(first.id()));
        assertThat(enrollment.mine("TEST_S", semester.id())).hasSize(2);
    }

    // 验证批选与单选同时提交时，批次不会只保存一部分。
    @Test
    void concurrentBatchAndSingleChoiceCannotLeavePartialBatch() throws Exception {
        var otherTeacher = management.createTeacher(new TeacherRequest("TEST_T2", "另一教师", "计算机学院", true, PASSWORD));
        var first = openClass(course("RACE_A", "2.0"), teacher, 10, 5, 1, 2, "测试教室 A");
        var conflict = openClass(course("RACE_B", "2.0"), otherTeacher, 10, 5, 2, 3, "测试教室 B");
        var unrelated = openClass(course("RACE_C", "2.0"), teacher, 10, 6, 1, 2, "测试教室 A");
        assertThat(race(() -> enrollment.enrollBatch("TEST_S", List.of(unrelated.id(), first.id())),
                () -> enrollment.enroll("TEST_S", conflict.id()))).isEqualTo(1);
        assertThat(mapper.selectedCount(first.id()) + mapper.selectedCount(conflict.id())).isEqualTo(1);
        assertThat(mapper.selectedCount(unrelated.id())).isEqualTo(mapper.selectedCount(first.id()));
    }

    // 覆盖学分上限、同课重复选择与名单所属教师的检查。
    @Test
    void creditsDuplicateCoursesAndRosterOwnershipAreChecked() {
        var firstCourse = course("LIMIT_C1","6.0");
        var first = openClass(firstCourse,teacher,10,1,1,2,"测试教室 G");
        var sameCourse = openClass(firstCourse,teacher,10,2,1,2,"测试教室 H");
        var extra = openClass(course("LIMIT_C2","4.1"),teacher,10,3,1,2,"测试教室 I");
        enrollment.enroll("TEST_S",first.id());
        assertThatThrownBy(() -> enrollment.enroll("TEST_S",sameCourse.id())).hasMessageContaining("不能重复");
        assertThatThrownBy(() -> enrollment.enroll("TEST_S",extra.id())).hasMessageContaining("学分上限");
        assertThatThrownBy(() -> management.updateSemester(semester.id(),new SemesterRequest(semester.name(),semester.startDate(),semester.endDate(),semester.selectionStart(),semester.selectionEnd(),new BigDecimal("5.0")))).hasMessageContaining("学分上限");
        var otherTeacher = management.createTeacher(new TeacherRequest("TEST_T2","另一教师","其他院系",true,PASSWORD));
        assertThatThrownBy(() -> teaching.roster(otherTeacher.teacherNo(),false,first.id())).isInstanceOf(BusinessException.class).hasMessageContaining("本人授课");
    }

    // 验证教室、任课教师以及有选课历史的排课都受到保护。
    @Test
    void teacherRoomAndHistoricalScheduleConflictsAreRejected() {
        var c = course("ROOM_C","2.0");
        var first = openClass(c,teacher,2,1,1,2,"共享教室");
        assertThatThrownBy(() -> openClass(course("ROOM_C2","1.0"),teacher,2,1,2,3,"另一教室")).hasMessageContaining("教师上课时间");
        var otherTeacher = management.createTeacher(new TeacherRequest("TEST_T2","另一教师","计算机学院",true,PASSWORD));
        assertThatThrownBy(() -> openClass(course("ROOM_C3","1.0"),otherTeacher,2,1,1,2,"共享教室")).hasMessageContaining("教室");
        enrollment.enroll("TEST_S",first.id());
        var changed = new ClassRequest(first.classCode(),first.courseId(),first.teacherId(),first.semesterId(),2,"PUBLISHED",List.of(new ScheduleRequest(2,1,2,1,16,"共享教室")));
        assertThatThrownBy(() -> management.updateClass(first.id(),changed)).hasMessageContaining("历史");
        management.updateClass(first.id(),edit(first,3,"CLOSED"));
        enrollment.drop("TEST_S",mapper.existingEnrollment(student.id(),first.id()));
        assertThat(mapper.selectedCount(first.id())).isZero();
    }

    // 验证管理员停用账号或重置密码后，旧会话无法继续访问。
    @Test
    void disabledAccountsAndPasswordResetsInvalidateExistingSessions() throws Exception {
        var session = login("TEST_S",PASSWORD);
        management.updateStudent(student.id(),new StudentRequest("TEST_S","测试学生","计算机","测试一班",false,null));
        mvc.perform(get("/api/courses").session(session)).andExpect(status().isUnauthorized());
        management.updateStudent(student.id(),new StudentRequest("TEST_S","测试学生","计算机","测试一班",true,null));
        var beforeReset = login("TEST_S",PASSWORD);
        management.resetPassword("students",student.id(),"Changed123456!");
        mvc.perform(get("/api/courses").session(beforeReset)).andExpect(status().isUnauthorized());
        var afterReset = login("TEST_S","Changed123456!");
        mvc.perform(get("/api/courses").session(afterReset)).andExpect(status().isOk());
    }

    // 通过 HTTP 请求验证角色限制、表单校验和退课归属检查。
    @Test
    void apiChecksRolesValidationAndPersonalDataOwnership() throws Exception {
        var studentSession = login("TEST_S",PASSWORD);
        mvc.perform(post("/api/admin/courses").session(studentSession).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/courses").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"courseCode\":\"BAD\",\"name\":\"测试\",\"credits\":-1,\"hours\":0}"))
                .andExpect(status().isBadRequest());
        var offered = openClass(course("OWNER_C","1.0"),teacher,10,7,1,2,"归属测试教室");
        var selected = enrollment.enroll("TEST_S",offered.id());
        management.createStudent(new StudentRequest("TEST_S2","另一学生","计算机","测试一班",true,PASSWORD));
        var otherSession = login("TEST_S2",PASSWORD);
        mvc.perform(delete("/api/enrollments/"+selected.info().id()).session(otherSession).with(csrf())).andExpect(status().isForbidden());
    }

    // 验证自助改密需要当前密码，并使同账号的其他旧会话失效。
    @Test
    void ownPasswordChangeChecksOldPasswordAndRevokesOtherSessions() throws Exception {
        var current = login("TEST_S", PASSWORD);
        var other = login("TEST_S", PASSWORD);
        mvc.perform(post("/api/auth/password").session(current).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("currentPassword","incorrect","newPassword","NewPassword123!"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/password").session(current).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("currentPassword",PASSWORD,"newPassword","NewPassword123!"))))
                .andExpect(status().isOk());
        assertThat(current.isInvalid()).isTrue();
        mvc.perform(get("/api/courses").session(other)).andExpect(status().isUnauthorized());
        var replacement = login("TEST_S", "NewPassword123!");
        mvc.perform(get("/api/courses").session(replacement)).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action FROM audit_log", String.class)).containsExactly("PASSWORD_CHANGE");
    }

    // 通过登录接口为指定测试角色创建 HTTP 会话。
    private MockHttpSession login(String username, String password) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username",username,"password",password))))
                .andExpect(status().isOk());
        return session;
    }
    // 同时放行两个操作，收集双方结果以判断竞争行为。
    private int race(Callable<?> first, Callable<?> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        // 同时放行并发任务，使请求真的竞争同一条业务记录。
        var gate = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> attempt(gate,first));
            var b = executor.submit(() -> attempt(gate,second));
            gate.countDown();
            return (a.get(15,TimeUnit.SECONDS) ? 1 : 0) + (b.get(15,TimeUnit.SECONDS) ? 1 : 0);
        } finally { executor.shutdownNow(); }
    }
    // 执行一次选课操作并将预期的业务失败转成可比较的结果。
    private boolean attempt(CountDownLatch gate, Callable<?> call) throws Exception {
        gate.await();
        try { call.call(); return true; }
        catch (BusinessException exception) { return false; }
    }
}
