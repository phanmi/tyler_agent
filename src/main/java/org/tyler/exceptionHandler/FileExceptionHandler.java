package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;

/**
 * Handles file read and write failures.
 */
@RestControllerAdvice
public class FileExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FileExceptionHandler.class);

    @ExceptionHandler(FileReadException.class)
    public ResponseEntity<ErrorResponse> handleFileRead(FileReadException ex) {
        log.warn("Failed to read file: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Failed to read file: " + ex.getMessage()));
    }

    @ExceptionHandler(FileWriteException.class)
    public ResponseEntity<ErrorResponse> handleFileWrite(FileWriteException ex) {
        log.warn("Failed to write file: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Failed to write file: " + ex.getMessage()));
    }
}