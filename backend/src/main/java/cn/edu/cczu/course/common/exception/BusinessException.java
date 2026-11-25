package cn.edu.cczu.course.common.exception;

import org.springframework.http.HttpStatus;

/** 由业务层抛出可向用户解释的异常。 */
public class BusinessException extends RuntimeException {
    // 业务规则主动拒绝一次请求时保存对应的状态，由全局处理器设置 HTTP 响应。
    private final HttpStatus status;

    // 保存业务失败的原因及对应的 HTTP 状态。
    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    // 把异常对应的 HTTP 状态交给统一错误处理。
    public HttpStatus getStatus() {
        return status;
    }
}
