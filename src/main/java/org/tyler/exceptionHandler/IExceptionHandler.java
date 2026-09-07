package org.tyler.exceptionHandler;

/**
 * 异常处理器契约：所有 {@code @RestControllerAdvice} 异常处理器都实现本接口，
 * 共享统一的错误响应结构 {@link ErrorResponse}。
 */
public interface IExceptionHandler {

    /** 统一的错误响应体。 */
    record ErrorResponse(String error) {}
}