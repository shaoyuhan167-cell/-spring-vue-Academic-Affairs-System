package cn.edu.cczu.course.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 承接本机免密账号切换时提交的用户名。 */
// 用户选择的是已启用账号的用户名，实际身份通过后端查询得到。
public record LocalAccountLoginRequest(
        @NotBlank(message = "请选择账号") @Size(max = 50, message = "账号不能超过 50 个字符") String username) {
}
