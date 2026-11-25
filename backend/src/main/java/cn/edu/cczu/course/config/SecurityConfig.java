package cn.edu.cczu.course.config;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.auth.security.AccountStatusFilter;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import java.io.IOException;

/** 配置后端的接口访问权限、登录会话和 CSRF 防护。 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    // 提供密码哈希算法，供登录验证、开户和改密使用。
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 从 Spring Security 配置取得认证管理器，用它验证登录凭据。
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    // 把登录后的安全上下文存进 HTTP 会话，方便后续请求复用身份。
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    // 在请求到达业务接口前，统一应用角色规则、会话配置和安全响应。
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                           SecurityContextRepository repository, UserMapper userMapper) throws Exception {
        // 使用服务端 Session；保留 CSRF 防护，前端先获取令牌再发送写请求。
        // 禁止当前应用被嵌入其他页面，并限制页面可加载的资源来源。
        // 下面的访问规则按声明顺序判断，具体角色的路径放在通用 API 规则前面。
        http.headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self'; font-src 'self' data:; connect-src 'self'; "
                                        + "frame-ancestors 'none'; object-src 'none'; base-uri 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN))
                        .frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/system/health", "/api/auth/csrf", "/api/auth/local-accounts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/local-login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/enrollments/**", "/api/timetable").hasRole("STUDENT")
                        .requestMatchers("/api/teaching/me/**").hasRole("TEACHER")
                        .requestMatchers("/api/teaching/classes/**").hasAnyRole("ADMIN", "TEACHER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .securityContext(context -> context.securityContextRepository(repository))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, 401, "请先登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, objectMapper, 403, "无操作权限或安全令牌已失效，请刷新后重试")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            objectMapper.writeValue(response.getWriter(), ApiResponse.success(null));
                        }));
        // 先重新确认旧会话中的账号是否有效，再按照角色判断该请求能否访问接口。
        http.addFilterBefore(new AccountStatusFilter(userMapper, objectMapper), AuthorizationFilter.class);
        return http.build();
    }

    // 按项目的 JSON 格式写出安全过滤器拦截产生的错误。
    private static void writeError(HttpServletResponse response, ObjectMapper mapper,
                                   int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}
