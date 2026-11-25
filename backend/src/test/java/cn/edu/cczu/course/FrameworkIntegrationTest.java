package cn.edu.cczu.course;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.edu.cczu.course.config.DemoDataInitializer;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import org.springframework.boot.DefaultApplicationArguments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** 使用真实数据库与认证链验证框架的登录、权限和数据查询。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrameworkIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DemoDataInitializer demoDataInitializer;
    @Autowired
    private UserMapper userMapper;

    // 验证浏览器能直接打开前端路由，业务 API 仍要求登录。
    @Test
    void frontendRoutesArePublicAndApiStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(get("/dashboard")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/audit-logs")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/system/overview")).andExpect(status().isUnauthorized());
    }

    // 重复执行样例初始化，检查已有账号与课程数据得以保留。
    @Test
    void sampleInitializationDoesNotOverwriteAccountsOrDuplicateCourses() throws Exception {
        // 先记住已有管理员，再重复执行样例初始化，核对关键数据是否保持原值。
        var originalAccount = userMapper.findByUsername("admin");
        demoDataInitializer.run(new DefaultApplicationArguments());
        var existingAccount = userMapper.findByUsername("admin");
        assertThat(existingAccount.id()).isEqualTo(originalAccount.id());
        assertThat(existingAccount.passwordHash()).isEqualTo(originalAccount.passwordHash());
        CsrfSession admin = login("admin", "ADMIN");
        mvc.perform(get("/api/system/overview").session(admin.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courseCount").value(3))
                .andExpect(jsonPath("$.data.teachingClassCount").value(3));
    }

    // 对比匿名用户访问健康检查和课程目录时的权限。
    @Test
    void anonymousCanCheckHealthButCannotReadCourses() throws Exception {
        mvc.perform(get("/api/system/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").value("H2"));
        mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // 验证默认配置下没有可以使用的本机免密入口。
    @Test
    void localAccountSwitchIsOffByDefault() throws Exception {
        mvc.perform(get("/api/auth/local-accounts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.accounts").isEmpty());
        CsrfSession csrf = csrf(new MockHttpSession());
        mvc.perform(post("/api/auth/local-login").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isForbidden());
    }

    // 分别验证缺少 CSRF 令牌以及凭据错误时的响应。
    @Test
    void loginRequiresCsrfAndRejectsInvalidCredentials() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Demo123456!\"}"))
                .andExpect(status().isForbidden());
        CsrfSession csrf = csrf(new MockHttpSession());
        mvc.perform(post("/api/auth/login").session(csrf.session())
                        .header(csrf.header(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
        mvc.perform(get("/api/auth/me").session(csrf.session())).andExpect(status().isUnauthorized());
    }

    // 在连续失败达到阈值后验证正确密码也需等待限制解除。
    @Test
    void loginReturns429AfterFiveFailuresEvenWithTheCorrectPassword() throws Exception {
        var endpoint = "/api/auth/login";
        var csrf = csrf(new MockHttpSession());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post(endpoint).session(csrf.session()).header(csrf.header(), csrf.token())
                            .with(request -> { request.setRemoteAddr("192.0.2.44"); return request; })
                            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post(endpoint).session(csrf.session()).header(csrf.header(), csrf.token())
                        .with(request -> { request.setRemoteAddr("192.0.2.44"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"Demo123456!\"}"))
                .andExpect(status().isTooManyRequests());
    }

    // 验证管理员可以读取概览，退出后旧会话无法继续访问。
    @Test
    void adminSessionCanReadOverviewAndLogoutInvalidatesIt() throws Exception {
        CsrfSession csrf = login("admin", "ADMIN");
        mvc.perform(get("/api/auth/me").session(csrf.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(get("/api/system/overview").session(csrf.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentCount").value(1))
                .andExpect(jsonPath("$.data.courseCount").value(3));
        mvc.perform(post("/api/auth/logout").session(csrf.session()).header(csrf.header(), csrf.token()))
                .andExpect(status().isOk());
        assertThat(csrf.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    // 验证学生可以浏览课程目录，管理员概览受到角色限制。
    @Test
    void studentCanReadCatalogButCannotReadAdminOverview() throws Exception {
        CsrfSession csrf = login("20260001", "STUDENT");
        mvc.perform(get("/api/courses").session(csrf.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].courseName").value("Web 应用开发"))
                .andExpect(jsonPath("$.data[0].selectedCount").value(0));
        mvc.perform(get("/api/system/overview").session(csrf.session())).andExpect(status().isForbidden());
    }

    // 验证教师能够登录，并只享有相应角色的权限。
    @Test
    void teacherCanAuthenticateButCannotReadAdminOverview() throws Exception {
        CsrfSession csrf = login("T2026001", "TEACHER");
        mvc.perform(get("/api/courses").session(csrf.session())).andExpect(status().isOk());
        mvc.perform(get("/api/system/overview").session(csrf.session())).andExpect(status().isForbidden());
    }

    // 检查表单错误和不存在的接口都使用统一错误格式。
    @Test
    void validationAndUnknownApiReturnUnifiedErrors() throws Exception {
        CsrfSession csrf = csrf(new MockHttpSession());
        mvc.perform(post("/api/auth/login").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        CsrfSession admin = login("admin", "ADMIN");
        mvc.perform(get("/api/does-not-exist").session(admin.session())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // 在测试中登录指定账号并取得后续请求使用的会话。
    private CsrfSession login(String username, String role) throws Exception {
        CsrfSession csrf = csrf(new MockHttpSession());
        String originalSessionId = csrf.session().getId();
        mvc.perform(post("/api/auth/login").session(csrf.session()).header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", "Demo123456!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(role));
        assertThat(csrf.session().getId()).isNotEqualTo(originalSessionId);
        return csrf;
    }

    // 为准备发送的测试请求取得匹配的会话和 CSRF 令牌。
    private CsrfSession csrf(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        JsonNode token = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new CsrfSession(session, token.path("headerName").asText(), token.path("token").asText());
    }

    // 一起保存一个测试会话及其 CSRF 请求头所需的数据。
    private record CsrfSession(MockHttpSession session, String header, String token) { }
}
