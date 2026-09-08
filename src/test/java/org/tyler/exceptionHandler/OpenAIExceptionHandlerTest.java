package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 直接对 {@link OpenAIExceptionHandler} 做单元测试：OpenAIKeyException 应映射为 401。
 */
class OpenAIExceptionHandlerTest {

    private final OpenAIExceptionHandler handler = new OpenAIExceptionHandler();

    @Test
    void openAIKeyExceptionReturns401() {
        var response = handler.handleOpenAIKey(new OpenAIKeyException("此 API Key 错误或不可用"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("此 API Key 错误或不可用", response.getBody().error());
    }
}
