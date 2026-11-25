package cn.edu.cczu.course.module.auth.security;

import cn.edu.cczu.course.module.user.model.UserAccount;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 保存凭据版本指纹，用于使修改密码前的 Session 失效。 */
public final class AccountPrincipal extends User {
    private final String credentialStamp;
    private final String role;

    public AccountPrincipal(UserAccount account) {
        super(account.username(), account.passwordHash(), account.enabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
        this.credentialStamp = fingerprint(account.passwordHash());
        this.role = account.role();
    }

    // 确认数据库账号仍启用，而且角色和密码凭据版本与会话一致。
    // 账号停用、角色变化或密码哈希发生变化时，原会话的身份自动失效。
    public boolean matches(UserAccount account) {
        return account != null && account.enabled() && role.equals(account.role())
                && credentialStamp.equals(fingerprint(account.passwordHash()));
    }

    // 生成密码哈希的摘要，供会话判断凭据是否发生过变更。
    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境缺少 SHA-256 算法", exception);
        }
    }
}
