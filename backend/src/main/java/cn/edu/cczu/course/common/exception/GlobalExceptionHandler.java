package cn.edu.cczu.course.common.exception;

import cn.edu.cczu.course.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;

/** 把校验错误和业务异常整理为统一的接口响应，便于前端展示提示。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 把数据库约束冲突转成用户可理解的业务提示。
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataConflict() {
        return error(HttpStatus.CONFLICT, "编号已存在，或该数据仍被其他记录引用，请刷新后检查");
    }

    // 数据库写入暂时无法完成时，提示稍后重试。
    @ExceptionHandler(TransientDataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusy() {
        return error(HttpStatus.CONFLICT, "当前操作繁忙或数据已变化，请刷新后重试");
    }

    // 字段类型不匹配或请求参数不符合要求时，返回参数错误。
    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidParameter() {
        return error(HttpStatus.BAD_REQUEST, "请求参数格式或取值不正确");
    }

    // 把业务异常携带的 HTTP 状态和提示原样交给客户端。
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return error(exception.getStatus(), exception.getMessage());
    }

    // 把表单字段校验失败的原因整理成一条响应。
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        // 把校验失败发生的字段和原因整理成前端可以直接展示的提示。
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getDefaultMessage()).findFirst().orElse("请求参数不正确");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    // 请求体缺失或无法按约定结构解析时，返回格式错误。
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody() {
        return error(HttpStatus.BAD_REQUEST, "请求体必须为有效的 JSON");
    }

    // 把未通过身份校验的请求转成统一的登录提示。
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication() {
        return error(HttpStatus.UNAUTHORIZED, "账号或密码错误");
    }

    // 身份有效但权限不足时，返回禁止访问。
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied() {
        return error(HttpStatus.FORBIDDEN, "当前账号无权执行此操作");
    }

    // 找不到请求对应的资源时返回 404。
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound() {
        return error(HttpStatus.NOT_FOUND, "请求的接口不存在");
    }

    // 将意外错误写进日志，客户端只收到通用提示。
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("请求处理失败", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "系统暂时不可用，请稍后重试");
    }

    // 同时设置 HTTP 状态和 JSON 业务错误码，统一错误响应格式。
    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), message));
    }
}
