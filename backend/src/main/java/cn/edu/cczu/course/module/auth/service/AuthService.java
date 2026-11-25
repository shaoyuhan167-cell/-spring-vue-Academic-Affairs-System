package cn.edu.cczu.course.module.auth.service;

import cn.edu.cczu.course.common.exception.BusinessException;
import cn.edu.cczu.course.module.auth.dto.CurrentUser;
import cn.edu.cczu.course.module.auth.dto.LoginRequest;
import cn.edu.cczu.course.module.audit.service.AuditService;
import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.module.user.model.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import cn.edu.cczu.course.module.academic.mapper.AcademicMapper;
import static cn.edu.cczu.course.module.academic.service.AcademicSupport.*;

/** 验证登录凭据、查询当前账号信息，并处理密码更换。 */
@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserMapper userMapper;
    private final AcademicMapper academicMapper;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AuthService(AuthenticationManager authenticationManager, UserMapper userMapper,
                       AcademicMapper academicMapper, PasswordEncoder encoder, AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.userMapper = userMapper;
        this.academicMapper = academicMapper;
        this.encoder = encoder;
        this.audit = audit;
    }

    // 把用户名和密码交给 Spring Security 认证管理器核对。
    public Authentication authenticate(LoginRequest request) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
    }

    // 返回页面所需的当前账号信息，省去响应中不需要的密码字段。
    public CurrentUser currentUser(String username) {
        UserAccount account = userMapper.findByUsername(username);
        if (account == null || !account.enabled()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "账号已失效，请重新登录");
        }
        return CurrentUser.from(account);
    }

    // 检查当前密码后保存新的密码哈希。
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        var account = require(userMapper.findByUsername(username), "账号不存在");
        // 数据库里保存的是密码哈希，用 BCrypt 校验当前密码。输入正确后再更新新密码相关记录。
        if (!encoder.matches(currentPassword, account.passwordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "原密码不正确");
        }
        valid(!encoder.matches(newPassword, account.passwordHash()), "新密码不能与原密码相同");
        academicMapper.updatePassword(account.id(), encoder.encode(newPassword));
        audit.record(username, "PASSWORD_CHANGE", "SELF", account.id(), "本人修改密码");
    }
}
