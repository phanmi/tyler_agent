package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 处理文件异常之外的通用异常（参数校验、未预期错误、资源未找到等）。
 */
@RestControllerAdvice
public class GenericExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GenericExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        log.warn("请求参数校验失败：{}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // 请求体 JSON 无法解析（格式错误 / 字段类型不匹配，如 Age 传了小数）。
        // 这是客户端输入问题，应返回 400，而非兜底的 500。
        log.warn("请求体解析失败：{}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse("请求体格式错误或字段类型不匹配"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleServerError(Exception ex) {
        log.error("未预期的服务器错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("服务器内部错误，请稍后重试"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Resource not found"));
    }
}