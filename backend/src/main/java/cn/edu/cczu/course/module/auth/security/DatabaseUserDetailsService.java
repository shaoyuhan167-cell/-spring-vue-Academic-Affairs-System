package cn.edu.cczu.course.module.auth.security;

import cn.edu.cczu.course.module.user.mapper.UserMapper;
import cn.edu.cczu.course.module.user.model.UserAccount;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 给 Spring Security 提供按用户名从数据库读取登录身份的方式。 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserMapper userMapper;

    public DatabaseUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    // 从数据库加载账号，再构造成认证框架能够识别的身份对象。
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userMapper.findByUsername(username);
        // 找不到用户名时让认证框架按凭据无效处理，接口由统一认证错误响应提供提示。
        if (account == null) {
            throw new UsernameNotFoundException("账号不存在");
        }
        return new AccountPrincipal(account);
    }
}
