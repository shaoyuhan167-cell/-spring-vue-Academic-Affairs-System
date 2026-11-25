package cn.edu.cczu.course.module.user.mapper;

import cn.edu.cczu.course.module.auth.dto.CurrentUser;
import cn.edu.cczu.course.module.user.model.UserAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 负责查询和更新账号，供登录、账号维护和会话检查使用。 */
public interface UserMapper {
    // 从账号表读取一个用户名对应的账号信息。
    @Select("SELECT id, username, password_hash, display_name, role, enabled FROM sys_user WHERE username = #{username}")
    UserAccount findByUsername(String username);

    // 列出可登录的账号摘要，供本机免密账号选择器使用。
    // 查询只映射 CurrentUser 需要的字段，页面无需拿到密码相关数据。
    @Select("SELECT id, username, display_name, role FROM sys_user WHERE enabled = 1 ORDER BY username")
    List<CurrentUser> enabledAccounts();

    @Insert("""
            INSERT INTO sys_user (username, password_hash, display_name, role, enabled)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{role}, #{enabled})
            """)
    // 将用户名、密码哈希、显示姓名和角色写入账号表。
    int insert(UserAccount account);
}
