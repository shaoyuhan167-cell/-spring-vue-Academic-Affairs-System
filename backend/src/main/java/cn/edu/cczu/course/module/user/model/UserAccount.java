package cn.edu.cczu.course.module.user.model;

/** 数据库账号实体，仅在服务端使用，禁止将密码散列返回给客户端。 */
// 这是服务端直接映射的账号记录。需要返回给页面时，使用 CurrentUser 剔除认证内部字段。
public record UserAccount(Long id, String username, String passwordHash, String displayName,
                          String role, boolean enabled) {
}
