package cn.edu.cczu.course;

import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.module.user.model.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证本机演示账号切换的生效条件和切换后的会话权限。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:local_account_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "server.address=127.0.0.1",
        "app.local-account-switch.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LocalAccountSwitchIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;

    // 验证候选列表只展示可用账号的基本信息，停用账号无法切换。
    @Test
    @Transactional
    void listsEnabledAccountsWithoutPasswordsAndRejectsDisabledAccounts() throws Exception {
        var sample = users.findByUsername("20260001");
        users.insert(new UserAccount(null, "DISABLED_USER", sample.passwordHash(), "停用账号", "STUDENT", false));
        mvc.perform(get("/api/auth/local-accounts").with(local()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.accounts[*].username", containsInAnyOrder("20260001", "T2026001", "admin")))
                .andExpect(jsonPath("$.data.accounts[*].role", containsInAnyOrder("STUDENT", "TEACHER", "ADMIN")))
                .andExpect(jsonPath("$.data.accounts[*].passwordHash").doesNotExist());
        mvc.perform(post("/api/auth/local-login").with(local()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"DISABLED_USER\"}"))
                .andExpect(status().isForbidden());
    }

    // 验证演示切换只接受符合要求的本机请求。
    @Test
    void blocksRemoteRequestsReboundHostsAndForwardedRequests() throws Exception {
        RequestPostProcessor remote = request -> {
            request.setRemoteAddr("192.0.2.10");
            return request;
        };
        mvc.perform(get("/api/auth/local-accounts").with(local()).with(remote))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.accounts").isEmpty());
        mvc.perform(post("/api/auth/local-login").with(local()).with(remote).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/local-login").with(local()).with(csrf())
                        .header("X-Forwarded-For", "192.0.2.10")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/local-accounts").with(local()).with(request -> {
                    request.setServerName("example.test");
                    return request;
                })).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
    }

    // 从一个角色切到另一个角色，验证旧会话被替换且权限随身份变化。
    @Test
    void switchingRolesReplacesTheSessionAndEnforcesTheNewPermissions() throws Exception {
        mvc.perform(post("/api/auth/local-login").with(local()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isForbidden());

        var student = switchTo(new MockHttpSession(), "20260001", "STUDENT");
        mvc.perform(get("/api/enrollments").session(student)).andExpect(status().isOk());
        mvc.perform(get("/api/system/overview").session(student)).andExpect(status().isForbidden());

        // 用前一个登录会话发起角色切换，随后检查新会话允许的操作与原会话是否仍可访问。
        var teacher = switchTo(student, "T2026001", "TEACHER");
        mvc.perform(get("/api/teaching/me/classes").session(teacher)).andExpect(status().isOk());
        mvc.perform(get("/api/enrollments").session(teacher)).andExpect(status().isForbidden());

        var admin = switchTo(teacher, "admin", "ADMIN");
        mvc.perform(get("/api/system/overview").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").param("action", "LOCAL_SWITCH").param("size", "1").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].actorRole").value("TEACHER"))
                .andExpect(jsonPath("$.data[0].targetId").value(users.findByUsername("admin").id()));

        var backToStudent = switchTo(admin, "20260001", "STUDENT");
        mvc.perform(get("/api/auth/me").session(backToStudent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("20260001"));
        mvc.perform(get("/api/system/overview").session(backToStudent)).andExpect(status().isForbidden());
    }

    // 携带会话和防伪令牌发出本机切换请求。
    private MockHttpSession switchTo(MockHttpSession previous, String username, String role) throws Exception {
        var result = mvc.perform(post("/api/auth/local-login").with(local()).session(previous).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", username))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(role)).andReturn();
        assertThat(previous.isInvalid()).isTrue();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // 为模拟请求设置本机连接信息。
    private RequestPostProcessor local() {
        return request -> {
            request.setLocalAddr("127.0.0.1");
            request.setRemoteAddr("127.0.0.1");
            request.setServerName("localhost");
            return request;
        };
    }
}
