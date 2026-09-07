package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 直接对 {@link GenericExceptionHandler} 的三个异常分支做单元测试：
 * 参数非法 → 400、未预期错误 → 500、资源未找到 → 404。
 */
class GenericExceptionHandlerTest {

    private final GenericExceptionHandler handler = new GenericExceptionHandler();

    @Test
    void illegalArgumentReturns400() {
        var response = handler.handleBadRequest(new IllegalArgumentException("bad input"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("bad input", response.getBody().error());
    }

    @Test
    void unexpectedExceptionReturns500() {
        var response = handler.handleServerError(new RuntimeException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("服务器内部错误，请稍后重试", response.getBody().error());
    }

    @Test
    void noResourceFoundReturns404() {
        var response = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.GET, "/api/missing", "/api/missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found", response.getBody().error());
    }
}
