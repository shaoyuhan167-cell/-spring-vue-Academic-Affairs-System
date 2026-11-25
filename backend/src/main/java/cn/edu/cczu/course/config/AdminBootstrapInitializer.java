package cn.edu.cczu.course.config;

import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.module.user.model.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 首次部署时显式创建管理员，不自动写入演示账号。 */
@Component
@Profile("sqlserver")
@ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
public class AdminBootstrapInitializer implements ApplicationRunner {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String password;

    public AdminBootstrapInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder,
                                     @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
    }

    // 按配置检查管理员是否已存在，必要时创建初始管理员。
    @Override
    // 管理员初始化需要显式启用。账号已经存在时跳过，避免每次启动时重建管理员。
    public void run(ApplicationArguments args) {
        if (userMapper.findByUsername("admin") != null) {
            return;
        }
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("首次初始化管理员须设置 BOOTSTRAP_ADMIN_PASSWORD，至少 12 个字符且不超过 72 字节");
        }
        userMapper.insert(new UserAccount(null, "admin", passwordEncoder.encode(password), "系统管理员", "ADMIN", true));
    }
}
