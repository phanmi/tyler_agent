package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 直接对 {@link SqlExceptionHandler} 的数据库异常分支做单元测试。
 *
 * <p>不走 Spring 容器，直接实例化 handler 调用其 {@code @ExceptionHandler} 方法，
 * 验证 {@link DataAccessResourceFailureException}（{@code DataAccessException} 的具体子类）被映射成 500 以及统一的错误文案。
 */
class SqlExceptionHandlerTest {

    private final SqlExceptionHandler handler = new SqlExceptionHandler();

    @Test
    void dataAccessExceptionReturns500() {
        var response = handler.handleDataAccess(new DataAccessResourceFailureException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("数据库操作失败，请稍后重试", response.getBody().error());
    }
}