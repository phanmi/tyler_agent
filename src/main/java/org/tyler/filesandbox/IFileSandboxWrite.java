package org.tyler.filesandbox;

/**
 * 文件沙箱的「只写」能力契约。
 *
 * <p>只暴露 {@code exists} 与 {@code write}，从类型层面就不具备读能力。
 * 需要「只写」能力时注入本接口即可，无需依赖完整实现。
 */
public interface IFileSandboxWrite {

    /** 判断沙箱内的文件是否存在。相对路径越界或为空会抛异常。 */
    boolean exists(String relativePath);

    /** 把内容写入沙箱内的文件，自动创建父目录。 */
    void write(String relativePath, String content);
}
