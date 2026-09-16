package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 处理 SQL / 数据库访问相关异常。
 *
 * <p>捕获 {@link DataAccessException}——Spring 对 JDBC {@code SQLException} 的统一翻译根类型，
 * 覆盖语法错误、连接失败、约束违反等全部 SQL 异常，统一映射为 500。
 */
@RestControllerAdvice
public class SqlExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SqlExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex) {
        log.error("数据库访问异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("数据库操作失败，请稍后重试"));
    }
}