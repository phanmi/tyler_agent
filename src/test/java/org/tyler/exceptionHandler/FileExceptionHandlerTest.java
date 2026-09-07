package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tyler.filesandbox.exceptions.FileReadException;
import org.tyler.filesandbox.exceptions.FileWriteException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 直接对 {@link FileExceptionHandler} 的两个异常分支做单元测试。
 *
 * <p>这里不走 Spring 容器，而是直接实例化 handler 并调用其 {@code @ExceptionHandler}
 * 方法（这些方法本身是 public 的，不依赖 {@code @RestControllerAdvice} 的装配），
 * 用于验证文件读写异常被映射成 500 以及具体的错误文案。
 */
class FileExceptionHandlerTest {

    private final FileExceptionHandler handler = new FileExceptionHandler();

    @Test
    void fileReadExceptionReturns500() {
        var response = handler.handleFileRead(new FileReadException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("读取文件失败：boom", response.getBody().error());
    }

    @Test
    void fileWriteExceptionReturns500() {
        var response = handler.handleFileWrite(new FileWriteException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("写入文件失败：boom", response.getBody().error());
    }
}
