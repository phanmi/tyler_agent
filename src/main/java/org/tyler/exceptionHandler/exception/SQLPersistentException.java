package org.tyler.exceptionHandler.exception;

/** SQLite 建表、插入、更新或删除失败时抛出。 */
public class SQLPersistentException extends RuntimeException {

    public SQLPersistentException(String message) {
        super(message);
    }

    public SQLPersistentException(String message, Throwable cause) {
        super(message, cause);
    }
}
