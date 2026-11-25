package cn.edu.cczu.course.module.auth.security;

import cn.edu.cczu.course.common.api.ApiResponse;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 停用账号、管理员重置密码或用户修改密码后，旧登录立即失效。 */
public class AccountStatusFilter extends OncePerRequestFilter {
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public AccountStatusFilter(UserMapper userMapper, ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    // 检查已登录账号是否仍有效，失效时清理旧会话并返回登录提示。
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // 只检查已经具备账号身份的 API 请求。页面静态文件和匿名请求继续由后续规则处理。
        if (request.getRequestURI().startsWith("/api/") && authentication != null
                && authentication.getPrincipal() instanceof AccountPrincipal principal
                && !principal.matches(userMapper.findByUsername(principal.getUsername()))) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(), ApiResponse.error(401, "账号状态或密码已变更，请重新登录"));
            return;
        }
        chain.doFilter(request, response);
    }
}
