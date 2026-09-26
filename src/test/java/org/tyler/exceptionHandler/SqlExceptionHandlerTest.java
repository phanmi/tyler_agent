package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the database exception branches in {@link SqlExceptionHandler}.
 *
 * <p>Calls the handler's exception methods directly without a Spring container.
 * Verifies that {@link DataAccessResourceFailureException}
 * and custom DAO exceptions return HTTP 500 with a consistent error message.
 */
class SqlExceptionHandlerTest {

    private final SqlExceptionHandler handler = new SqlExceptionHandler();

    @Test
    void dataAccessExceptionReturns500() {
        var response = handler.handleDataAccess(new DataAccessResourceFailureException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Database operation failed. Please try again later", response.getBody().error());
    }

    @Test
    void sqlPersistentExceptionReturns500() {
        var response = handler.handleSqlPersistent(new SQLPersistentException("Insertion did not return an ID"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Database operation failed. Please try again later", response.getBody().error());
    }

    @Test
    void sqlReadExceptionReturns500() {
        var response = handler.handleSqlRead(new SQLReadException("Missing SQL resource"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Database operation failed. Please try again later", response.getBody().error());
    }
}
