package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the three branches in {@link GenericExceptionHandler}:
 * invalid arguments return 400, unexpected errors 500, and missing resources 404.
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
        assertEquals("Internal server error. Please try again later", response.getBody().error());
    }

    @Test
    void noResourceFoundReturns404() {
        var response = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.GET, "/api/missing", "/api/missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found", response.getBody().error());
    }
}
