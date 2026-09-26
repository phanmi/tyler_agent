package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;

/**
 * Handles SQL and database access errors.
 *
 * <p>{@link DataAccessException} is Spring's translated JDBC exception base type
 * for syntax, connection, and constraint failures. DAO resource-read and
 * persistence exceptions are also mapped to HTTP 500.
 */
@RestControllerAdvice
public class SqlExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SqlExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex) {
        log.error("Database access failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Database operation failed. Please try again later"));
    }

    @ExceptionHandler(SQLPersistentException.class)
    public ResponseEntity<ErrorResponse> handleSqlPersistent(SQLPersistentException ex) {
        log.error("Database persistence failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Database operation failed. Please try again later"));
    }

    @ExceptionHandler(SQLReadException.class)
    public ResponseEntity<ErrorResponse> handleSqlRead(SQLReadException ex) {
        log.error("Failed to read the database or a SQL resource", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Database operation failed. Please try again later"));
    }
}
