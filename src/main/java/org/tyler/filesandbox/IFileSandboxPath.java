package org.tyler.filesandbox;

import java.nio.file.Path;

/**
 * 文件沙箱的「路径解析」能力契约。
 *
 * <p>只暴露 {@code resolve}，让需要把相对路径解析到沙箱内（如 SQLite 数据库文件路径）的
 * 调用方不必依赖完整的读写实现，与 read/write 复用同一套越界校验。
 */
public interface IFileSandboxPath {

    /** 把相对路径解析到沙箱内的绝对路径；路径为空或越界会抛异常。 */
    Path resolve(String relativePath);
}