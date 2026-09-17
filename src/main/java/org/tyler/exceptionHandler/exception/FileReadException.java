package org.tyler.exceptionHandler.exception;

/**
 * 读取工作区文件失败时抛出。
 */
public class FileReadException extends RuntimeException {

    public FileReadException(String message) {
        super(message);
    }

    public FileReadException(String message, Throwable cause) {
        super(message, cause);
    }
}