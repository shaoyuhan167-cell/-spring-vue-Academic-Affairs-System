package cn.edu.cczu.course.module.auth.service;

import cn.edu.cczu.course.common.exception.BusinessException;
import cn.edu.cczu.course.module.auth.dto.CurrentUser;
import cn.edu.cczu.course.module.auth.security.AccountPrincipal;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static cn.edu.cczu.course.module.auth.service.LoginThrottle.Method.LOCAL;

/** 为启用免密登录的本机部署提供身份选择与切换。 */
@Service
public class LocalAccountLogin {
    private static final Set<String> LOCAL_ADDRESSES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");
    private static final List<String> PROXY_HEADERS = List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP");

    private final UserMapper userMapper;
    private final LoginThrottle loginThrottle;
    private final boolean enabled;

    public LocalAccountLogin(UserMapper userMapper, LoginThrottle loginThrottle,
                             @Value("${app.local-account-switch.enabled:false}") boolean configured,
                             @Value("${server.address:}") String serverAddress) {
        this.userMapper = userMapper;
        this.loginThrottle = loginThrottle;
        // 配置开启之后还要求应用只监听回环地址。普通启动环境默认关闭这项本机功能。
        this.enabled = configured && LOCAL_HOSTS.contains(serverAddress.toLowerCase(Locale.ROOT));
    }

    // 描述账号切换开关状态及当前可选账号。
    public record Options(boolean enabled, List<CurrentUser> accounts) { }

    // 本机免密登录可用时返回启用的账号列表。
    public Options options(HttpServletRequest request) {
        boolean available = available(request);
        return new Options(available, available ? userMapper.enabledAccounts() : List.of());
    }

    // 在本机请求和账号状态满足要求时构造新的认证身份。
    public Authentication authenticate(HttpServletRequest request, String username) {
        if (!available(request)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "当前访问方式未启用本机免密登录");
        }
        var ip = request.getRemoteAddr();
        loginThrottle.assertAllowed(LOCAL, username, ip);
        var account = userMapper.findByUsername(username);
        if (account == null || !account.enabled()) {
            loginThrottle.onFailure(LOCAL, username, ip);
            throw new BusinessException(HttpStatus.FORBIDDEN, "账号不可用，请刷新用户列表");
        }
        loginThrottle.onSuccess(LOCAL, username, ip);
        var principal = new AccountPrincipal(account);
        // 本机选择完成后会话只需要账号身份和权限，因此清除身份对象中的密码凭据。
        principal.eraseCredentials();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    // 同时检查配置、监听地址和请求来源，确保免密切换仅用于本机回环请求。
    private boolean available(HttpServletRequest request) {
        // 同时限制监听地址、连接来源和请求主机，转发请求继续使用常规登录。
        return enabled && LOCAL_ADDRESSES.contains(request.getLocalAddr())
                && LOCAL_ADDRESSES.contains(request.getRemoteAddr())
                && LOCAL_HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT))
                && PROXY_HEADERS.stream().noneMatch(header -> request.getHeader(header) != null);
    }
}
