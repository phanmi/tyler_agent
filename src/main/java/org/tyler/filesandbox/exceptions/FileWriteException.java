package org.tyler.filesandbox;

/**
 * 写入工作区文件失败时抛出。
 */
public class FileWriteException extends RuntimeException {

    public FileWriteException(String message) {
        super(message);
    }

    public FileWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}