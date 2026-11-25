package cn.edu.cczu.course.module.auth.controller;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.auth.dto.CurrentUser;
import cn.edu.cczu.course.module.auth.dto.LocalAccountLoginRequest;
import cn.edu.cczu.course.module.auth.dto.LoginRequest;
import cn.edu.cczu.course.module.auth.service.AuthService;
import cn.edu.cczu.course.module.auth.service.LocalAccountLogin;
import cn.edu.cczu.course.module.auth.service.LoginThrottle;
import cn.edu.cczu.course.module.audit.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import cn.edu.cczu.course.module.academic.dto.AcademicRequests.ChangePasswordRequest;
import static cn.edu.cczu.course.module.auth.service.LoginThrottle.Method.PASSWORD;

/** 提供登录、退出、查询当前身份和修改密码的接口，并处理登录会话。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final LocalAccountLogin localAccountLogin;
    private final SecurityContextRepository contextRepository;
    private final AuditService audit;
    private final LoginThrottle loginThrottle;

    public AuthController(AuthService authService, LocalAccountLogin localAccountLogin,
                          SecurityContextRepository contextRepository, AuditService audit, LoginThrottle loginThrottle) {
        this.authService = authService;
        this.localAccountLogin = localAccountLogin;
        this.contextRepository = contextRepository;
        this.audit = audit;
        this.loginThrottle = loginThrottle;
    }

    // 把当前会话使用的 CSRF 令牌及请求头名称返回给页面。
    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.success(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    // 检查普通登录凭据，成功后保存身份并返回账号摘要。
    @PostMapping("/login")
    public ApiResponse<CurrentUser> login(@Valid @RequestBody LoginRequest body,
                                          HttpServletRequest request, HttpServletResponse response) {
        var ip = request.getRemoteAddr();
        // 认证前先校验失败次数。认证异常时累计失败，成功时清除此登录方式上的失败记录。
        loginThrottle.assertAllowed(PASSWORD, body.username(), ip);
        Authentication authentication;
        try {
            authentication = authService.authenticate(body);
        } catch (AuthenticationException exception) {
            loginThrottle.onFailure(PASSWORD, body.username(), ip);
            throw exception;
        }
        loginThrottle.onSuccess(PASSWORD, body.username(), ip);
        return establishSession(authentication, request, response);
    }

    // 返回当前请求能否使用本机免密登录，以及可选账号列表。
    @GetMapping("/local-accounts")
    public ApiResponse<LocalAccountLogin.Options> localAccounts(HttpServletRequest request) {
        return ApiResponse.success(localAccountLogin.options(request));
    }

    // 验证本机免密登录的账号，并建立该账号的会话。
    @PostMapping("/local-login")
    public ApiResponse<CurrentUser> localLogin(@Valid @RequestBody LocalAccountLoginRequest body,
                                              HttpServletRequest request, HttpServletResponse response) {
        var authentication = localAccountLogin.authenticate(request, body.username());
        var user = authService.currentUser(authentication.getName());
        // 本机首次选择账号与已登录用户切换身份使用同一接口。审计根据有无原身份记录对应操作者。
        var previousIdentity = SecurityContextHolder.getContext().getAuthentication();
        if (previousIdentity == null || !previousIdentity.isAuthenticated() || previousIdentity instanceof AnonymousAuthenticationToken) {
            audit.record(user.username(), "LOCAL_SWITCH", "SELF", user.id(), "本机免密登录");
        } else {
            audit.record(previousIdentity.getName(), "LOCAL_SWITCH", "ACCOUNT", user.id(), "本机免密切换身份");
        }
        // 切换身份时建立全新会话，旧身份的上下文和 CSRF 令牌一起失效。
        var previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        return establishSession(authentication, user, request, response);
    }

    // 从认证身份加载账号摘要，再交给共用方法建立会话。
    private ApiResponse<CurrentUser> establishSession(Authentication authentication, HttpServletRequest request,
                                                      HttpServletResponse response) {
        return establishSession(authentication, authService.currentUser(authentication.getName()), request, response);
    }

    // 创建或更新会话编号，再将认证身份写入安全上下文并保存到会话。
    private ApiResponse<CurrentUser> establishSession(Authentication authentication, CurrentUser user,
                                                      HttpServletRequest request, HttpServletResponse response) {
        // 自定义 JSON 登录须显式保存上下文，并轮换会话编号以防止会话固定攻击。
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return ApiResponse.success(user);
    }

    // 根据登录身份返回当前账号的基础信息。
    @GetMapping("/me")
    public ApiResponse<CurrentUser> me(Authentication authentication) {
        return ApiResponse.success(authService.currentUser(authentication.getName()));
    }

    // 检查当前密码后保存新的密码哈希。
    @PostMapping("/password")
    public ApiResponse<?> changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordRequest body,
                                         HttpServletRequest request) {
        // 服务层先验证并更新密码。仅在更新成功之后，才注销本次会话并让浏览器重新登录。
        authService.changePassword(authentication.getName(), body.currentPassword(), body.newPassword());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ApiResponse.success(null);
    }
}
