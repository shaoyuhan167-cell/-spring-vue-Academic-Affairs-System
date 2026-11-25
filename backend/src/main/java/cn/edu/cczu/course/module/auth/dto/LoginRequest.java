package cn.edu.cczu.course.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 承接普通登录时提交的用户名与密码。 */
// JSON 请求体由 Spring 转成这个记录，校验用户名与密码格式后才进入认证逻辑。
public record LoginRequest(
        @NotBlank(message = "请输入账号") @Size(max = 50, message = "账号不能超过 50 个字符") String username,
        @NotBlank(message = "请输入密码") @Size(max = 100, message = "密码不能超过 100 个字符") String password) {
}
