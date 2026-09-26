package org.tyler.exceptionHandler.exception;

/** 读取数据库记录或 SQLite DAO 所需的 SQL 资源失败时抛出。 */
public class SQLReadException extends RuntimeException {

    public SQLReadException(String message) {
        super(message);
    }

    public SQLReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
