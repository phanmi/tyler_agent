package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests both exception branches in {@link FileExceptionHandler}.
 *
 * <p>Instantiates the handler directly and calls its public exception methods
 * without starting a Spring container.
 * Verifies HTTP 500 status and the error messages for file access failures.
 */
class FileExceptionHandlerTest {

    private final FileExceptionHandler handler = new FileExceptionHandler();

    @Test
    void fileReadExceptionReturns500() {
        var response = handler.handleFileRead(new FileReadException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Failed to read file: boom", response.getBody().error());
    }

    @Test
    void fileWriteExceptionReturns500() {
        var response = handler.handleFileWrite(new FileWriteException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Failed to write file: boom", response.getBody().error());
    }
}
