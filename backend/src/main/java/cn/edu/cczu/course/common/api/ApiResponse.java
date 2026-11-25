package cn.edu.cczu.course.common.api;

/** 所有业务接口使用统一的数据结构，HTTP 状态码仍反映请求结果。 */
// 前端通用请求封装按 code 判断成败，成功时取出 data，失败时使用 message 显示原因。
public record ApiResponse<T>(int code, String message, T data) {
    // 将业务数据放入成功响应，前端以状态码 0 判断处理成功。
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "操作成功", data);
    }

    // 返回错误状态和可展示的提示，数据部分留空。
    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
