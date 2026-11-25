package cn.edu.cczu.course.module.auth.dto;

import cn.edu.cczu.course.module.user.model.UserAccount;

/** 定义前端识别当前账号所需的基本资料和角色。 */
// 登录响应供页面渲染显示名、菜单和身份信息，只包含这里声明的字段。
public record CurrentUser(Long id, String username, String displayName, String role) {
    // 将账号转换成前端使用的摘要，只带出公开的身份字段。
    public static CurrentUser from(UserAccount account) {
        return new CurrentUser(account.id(), account.username(), account.displayName(), account.role());
    }
}
