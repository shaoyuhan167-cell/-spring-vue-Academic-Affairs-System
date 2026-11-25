package cn.edu.cczu.course;

import cn.edu.cczu.course.config.DemoDataInitializer;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.*;
import cn.edu.cczu.course.module.academic.model.AcademicModels.*;
import cn.edu.cczu.course.module.academic.service.*;
import cn.edu.cczu.course.module.audit.mapper.AuditMapper;
import cn.edu.cczu.course.module.learning.dto.LearningRequests.*;
import cn.edu.cczu.course.module.learning.model.LearningModels.*;
import cn.edu.cczu.course.module.learning.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 在隔离数据库中验证成绩、资料、公告、导出的真实事务和权限。 */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:learning_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test") @AutoConfigureMockMvc
class LearningIntegrationTest {
    @Autowired ManagementService management; @Autowired EnrollmentService enrollments;
    @Autowired GradeService grades; @Autowired ProfileService profiles; @Autowired NoticeService notices;
    @Autowired ExportService exports; @Autowired JdbcTemplate jdbc; @Autowired DemoDataInitializer samples;
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @MockitoSpyBean AuditMapper auditMapper;
    ClassView offered;Semester semester;Long enrollmentId;
    // 为每个用例准备角色、教学班和选课等测试数据。
    // 每个成绩或公告用例先准备自己的基础数据，避免受其他用例执行顺序影响。
    @BeforeEach void prepare() {
        for(String table:List.of("audit_log","announcement_read","student_grade","user_profile","announcement","enrollment","course_schedule","teaching_class","semester","course","student","teacher","sys_user"))jdbc.update("DELETE FROM "+table);
        samples.run(new DefaultApplicationArguments());
        var now=LocalDateTime.now();
        semester=management.createSemester(new SemesterRequest("扩展测试学期",LocalDate.now().minusDays(30),LocalDate.now().plusDays(100),now.minusDays(1),now.plusDays(1),new BigDecimal("20")));
        var teacher=management.createTeacher(new TeacherRequest("LEARN_T","测试教师","学院",true,"Learn123456!"));
        management.createTeacher(new TeacherRequest("OTHER_T","其他教师","学院",true,"Learn123456!"));
        management.createStudent(new StudentRequest("LEARN_S","测试学生","软件工程","测试一班",true,"Learn123456!"));
        management.createStudent(new StudentRequest("OTHER_S","其他学生","软件工程","测试一班",true,"Learn123456!"));
        var course=management.createCourse(new CourseRequest("LEARN_C","扩展测试课程",new BigDecimal("3"),48,""));
        offered=management.createClass(new ClassRequest("LEARN_TC",course.id(),teacher.id(),semester.id(),10,"PUBLISHED",List.of(new ScheduleRequest(1,1,2,1,16,"测试室"))));
        enrollmentId=enrollments.enroll("LEARN_S",offered.id()).info().id();
    }
    // 把测试分数与选课记录编号整理成成绩提交表单。
    GradeBatch batch(String usual,String exam) {
        return new GradeBatch(30,grades.book("LEARN_T",offered.id()).rows().stream().map(r->new GradeEntry(r.enrollmentId(),usual==null?null:new BigDecimal(usual),exam==null?null:new BigDecimal(exam),"测试备注",r.revision())).toList());
    }
    // 构造公告测试用的标题、角色、时间和版本。
    NoticeRequest notice(String title,String role,String status,LocalDateTime from,LocalDateTime until,int revision) {return new NoticeRequest(title,"公告正文\n第二行",role,status,false,from,until,revision);}
    // 用测试公告的标题查回对应的数据库编号。
    Long noticeId(String title) {return jdbc.queryForObject("SELECT id FROM announcement WHERE title=?",Long.class,title);}

    // 核对成绩操作留下的审计内容并验证只有管理员可查询。
    @Test void gradeAuditRecordsActorTargetAndIpAndIsAdminOnly() throws Exception {
        grades.save("LEARN_T", offered.id(), batch("80", "90"));
        mvc.perform(post("/api/grades/classes/{id}/publish", offered.id()).with(user("LEARN_T").roles("TEACHER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch("80", "90"))))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> grades.changeStatus("LEARN_T", offered.id(), batch("80", "90"), true))
                .hasMessageContaining("已经发布");
        grades.changeStatus("LEARN_T", offered.id(), batch("80", "90"), false);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log ORDER BY id", String.class))
                .containsExactly("GRADE_SAVE", "GRADE_PUBLISH", "GRADE_WITHDRAW");
        mvc.perform(get("/api/admin/audit").param("action", "GRADE_PUBLISH").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].actorRole").value("TEACHER"))
                .andExpect(jsonPath("$.data[0].targetId").value(offered.id()))
                .andExpect(jsonPath("$.data[0].clientIp").value("127.0.0.1"));
        mvc.perform(get("/api/admin/audit").with(user("LEARN_S").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").with(user("LEARN_T").roles("TEACHER"))).andExpect(status().isForbidden());
    }

    // 模拟审计写入失败，检查成绩发布是否随事务一起回滚。
    @Test void gradePublicationRollsBackIfAuditCannotBeWritten() {
        grades.save("LEARN_T", offered.id(), batch("80", "90"));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("模拟审计写入失败"))
                .when(auditMapper).insert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("TEACHER"),
                        org.mockito.ArgumentMatchers.eq("GRADE_PUBLISH"), org.mockito.ArgumentMatchers.eq("TEACHING_CLASS"),
                        org.mockito.ArgumentMatchers.eq(offered.id()), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(LocalDateTime.class));
        assertThatThrownBy(() -> grades.changeStatus("LEARN_T", offered.id(), batch("80", "90"), true))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(grades.book("LEARN_T", offered.id()).publishedCount()).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_log", String.class)).containsExactly("GRADE_SAVE");
    }

    // 验证成绩草稿、发布、撤回及学生端加权统计的完整流程。
    @Test void gradesDraftPublishWithdrawAndWeightedSummary() {
        assertThat(grades.mine("LEARN_S",semester.id()).pendingCount()).isEqualTo(1);
        grades.save("LEARN_T",offered.id(),batch("80","90"));
        assertThat(grades.mine("LEARN_S",null).records()).isEmpty();
        var published=grades.changeStatus("LEARN_T",offered.id(),batch("80","90"),true);
        assertThat(published.publishedCount()).isEqualTo(1);
        var mine=grades.mine("LEARN_S",semester.id());
        assertThat(mine.average()).isEqualByComparingTo("87.00");assertThat(mine.earnedCredits()).isEqualByComparingTo("3");
        assertThat(mine.gpa()).isEqualByComparingTo("3.70");assertThat(mine.pendingCount()).isZero();
        assertThat(grades.mine("OTHER_S",null).records()).isEmpty();
        assertThatThrownBy(()->grades.save("LEARN_T",offered.id(),batch("60","60"))).hasMessageContaining("撤回");
        grades.changeStatus("admin",offered.id(),batch("80","90"),false);
        assertThat(grades.mine("LEARN_S",null).records()).isEmpty();
        grades.save("LEARN_T",offered.id(),batch("50","55"));
        grades.changeStatus("LEARN_T",offered.id(),batch("50","55"),true);
        assertThat(grades.mine("LEARN_S",null).earnedCredits()).isEqualByComparingTo("0");
    }
    // 覆盖分数不完整无法发布，以及旧版本无法覆盖新版本的场景。
    @Test void incompleteGradesAndStaleVersionsCannotPublishOrOverwrite() {
        var stale=batch("80","90");grades.save("LEARN_T",offered.id(),batch("80",null));
        assertThatThrownBy(()->grades.save("LEARN_T",offered.id(),stale)).hasMessageContaining("更新");
        assertThatThrownBy(()->grades.changeStatus("LEARN_T",offered.id(),batch("80",null),true)).hasMessageContaining("完整");
        assertThat(GradeService.total(new BigDecimal("80.01"),new BigDecimal("80.02"),30)).isEqualByComparingTo("80.02");
    }
    // 验证名单变化或重复条目会导致整批成绩保存失败。
    @Test void changedRosterAndDuplicateEntriesRollBackWholeBatch() {
        var before=batch("80","90");enrollments.enroll("OTHER_S",offered.id());
        assertThatThrownBy(()->grades.save("LEARN_T",offered.id(),before)).hasMessageContaining("名单");
        var duplicate=new GradeBatch(30,List.of(before.entries().getFirst(),before.entries().getFirst()));
        assertThatThrownBy(()->grades.save("LEARN_T",offered.id(),duplicate)).hasMessageContaining("重复");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_grade",Long.class)).isZero();
    }
    // 验证录入成绩后禁止退课，发布成绩后禁止新学生选进该班。
    @Test void recordedGradesProtectDropAndPublishedClassProtectsNewEnrollment() {
        grades.save("LEARN_T",offered.id(),batch("80",null));
        assertThatThrownBy(()->enrollments.drop("LEARN_S",enrollmentId)).hasMessageContaining("已录入成绩");
        grades.save("LEARN_T",offered.id(),batch(null,null));enrollments.drop("LEARN_S",enrollmentId);
        enrollments.enroll("LEARN_S",offered.id());grades.save("LEARN_T",offered.id(),batch("80","90"));
        grades.changeStatus("LEARN_T",offered.id(),batch("80","90"),true);
        assertThatThrownBy(()->enrollments.enroll("OTHER_S",offered.id())).hasMessageContaining("已发布成绩");
    }
    // 模拟两个教师端提交同一版本，确认后提交的一方无法覆盖。
    @Test void twoConcurrentEditorsCannotOverwriteSameRevision() throws Exception {
        var body=batch("70","80");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> task=()->{start.await();try {grades.save("LEARN_T",offered.id(),body);return true;}catch(cn.edu.cczu.course.common.exception.BusinessException e){return false;}};
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(grades.book("LEARN_T",offered.id()).rows().getFirst().revision()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    // 验证成绩接口按角色、教学班归属、字段限制和 CSRF 规则处理请求。
    @Test void gradeRoleOwnershipValidationAndCsrfAreEnforced() throws Exception {
        mvc.perform(get("/api/grades/classes/"+offered.id()).with(user("OTHER_T").roles("TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/grades/classes").with(user("LEARN_S").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/grades/mine").with(user("LEARN_T").roles("TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/grades/classes/"+offered.id()).with(user("LEARN_T").roles("TEACHER")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch("80","90")))).andExpect(status().isForbidden());
        mvc.perform(put("/api/grades/classes/"+offered.id()).with(user("LEARN_T").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch("101","90")))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/grades/classes/"+offered.id()).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].studentNo").value("LEARN_S"));
    }
    // 验证资料接口只处理当前用户，并只更新允许编辑的字段。
    @Test void profileIsPrivateAndAcademicFieldsCannotBeChanged() throws Exception {
        profiles.update("LEARN_S",new ProfileRequest("student@example.test","13800000000","我的简介"));
        assertThat(profiles.get("OTHER_S").email()).isEmpty();
        mvc.perform(put("/api/profile").with(user("LEARN_S").roles("STUDENT")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"new@example.test\",\"phone\":\"\",\"bio\":\"更新\",\"studentNo\":\"HACK\",\"userId\":1,\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.studentNo").value("LEARN_S")).andExpect(jsonPath("$.data.role").value("STUDENT"));
        mvc.perform(put("/api/profile").with(user("LEARN_S").roles("STUDENT")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"bad\",\"phone\":\"abc\",\"bio\":\"\"}")).andExpect(status().isBadRequest());
        assertThat(profiles.get("LEARN_S").email()).isEqualTo("new@example.test");
    }
    // 验证公告可见角色、展示时间以及内容更新后的已读状态。
    @Test void noticesRespectAudienceTimeAndUpdatedReadState() {
        var now=LocalDateTime.now();
        notices.create("admin",notice("学生通知","STUDENT","PUBLISHED",null,null,0));
        notices.create("admin",notice("教师通知","TEACHER","PUBLISHED",null,null,0));
        notices.create("admin",notice("未来通知","ALL","PUBLISHED",now.plusDays(1),null,0));
        notices.create("admin",notice("草稿","ALL","DRAFT",null,null,0));
        notices.create("admin",notice("已到期","ALL","PUBLISHED",null,now.plusDays(1),0));
        jdbc.update("UPDATE announcement SET expires_at=? WHERE title=?",now.minusDays(1),"已到期");
        assertThat(notices.list("LEARN_S",false,1,10,null,false).records()).extracting(Notice::title).containsExactly("学生通知");
        var id=noticeId("学生通知");assertThatThrownBy(()->notices.detail("LEARN_T",id)).hasMessageContaining("不可见");
        notices.read("LEARN_S",id,1);notices.read("LEARN_S",id,1);
        assertThat(notices.list("LEARN_S",false,1,10,null,true).total()).isZero();
        notices.update("admin",id,notice("学生通知已更新","STUDENT","PUBLISHED",null,null,1));
        assertThat(notices.list("LEARN_S",false,1,10,null,true).total()).isEqualTo(1);
        assertThatThrownBy(()->notices.read("LEARN_S",id,1)).hasMessageContaining("已更新");
        notices.read("LEARN_S",id,2);assertThat(notices.detail("LEARN_S",id).readAt()).isNotNull();
    }
    // 验证公告的历史记录保护与版本冲突检查。
    @Test void noticeHistoryAndRevisionProtectAdministration() throws Exception {
        notices.create("admin",notice("可删除草稿","ALL","DRAFT",null,null,0));var draft=noticeId("可删除草稿");
        notices.remove("admin",draft,1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM announcement",Long.class)).isZero();
        notices.create("admin",notice("正式公告","ALL","PUBLISHED",null,null,0));var id=noticeId("正式公告");
        notices.update("admin",id,notice("正式公告","ALL","DRAFT",null,null,1));
        assertThatThrownBy(()->notices.remove("admin",id,2)).hasMessageContaining("保留历史");
        assertThatThrownBy(()->notices.update("admin",id,notice("覆盖","ALL","DRAFT",null,null,1))).hasMessageContaining("修改");
        mvc.perform(post("/api/admin/announcements").with(user("LEARN_T").roles("TEACHER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(notice("非法","ALL","PUBLISHED",null,null,0)))).andExpect(status().isForbidden());
    }
    // 验证导出数据范围、筛选条件，以及 CSV 对公式样式文本的处理。
    @Test void exportsRespectScopeFiltersAndProtectFormulaText() throws Exception {
        mvc.perform(get("/api/exports/admin/students").with(user("LEARN_S").roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/exports/teaching/grades").param("classId",offered.id().toString()).with(user("OTHER_T").roles("TEACHER"))).andExpect(status().isForbidden());
        var response=mvc.perform(get("/api/exports/admin/students").param("keyword","LEARN_S").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        String csv=new String(response.getContentAsByteArray(),StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF").contains("LEARN_S").doesNotContain("OTHER_S","password","密码");
        assertThat(new String(exports.student("enrollments","OTHER_S",null,null,null),StandardCharsets.UTF_8)).doesNotContain("LEARN_S");
        assertThat(new String(exports.student("enrollments","LEARN_S",null,"无匹配",null),StandardCharsets.UTF_8)).doesNotContain("LEARN_S");
        assertThat(CsvWriter.cell(" =1+1")).isEqualTo("\"' =1+1\"");
        assertThat(CsvWriter.cell("甲,\"乙\"\n丙")).isEqualTo("\"甲,\"\"乙\"\"\n丙\"");
        assertThat(CsvWriter.cell(new BigDecimal("-1"))).isEqualTo("\"-1\"");
    }
}
