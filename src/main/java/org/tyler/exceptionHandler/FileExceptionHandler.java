package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tyler.filesandbox.exceptions.FileReadException;
import org.tyler.filesandbox.exceptions.FileWriteException;

/**
 * 处理文件读写相关异常。
 */
@RestControllerAdvice
public class FileExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FileExceptionHandler.class);

    @ExceptionHandler(FileReadException.class)
    public ResponseEntity<ErrorResponse> handleFileRead(FileReadException ex) {
        log.warn("读取文件失败：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("读取文件失败：" + ex.getMessage()));
    }

    @ExceptionHandler(FileWriteException.class)
    public ResponseEntity<ErrorResponse> handleFileWrite(FileWriteException ex) {
        log.warn("写入文件失败：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("写入文件失败：" + ex.getMessage()));
    }
}