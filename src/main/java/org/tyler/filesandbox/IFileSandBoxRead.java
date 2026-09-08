package org.tyler.filesandbox;

/**
 * 文件沙箱的「只读」能力契约。
 *
 * <p>只暴露 {@code exists} 与 {@code read}，从类型层面就不具备写能力。
 * 需要「只读」能力时注入实现本接口的 {@link FileSandBoxReadOnly}。
 */
public interface IFileSandBoxRead {

    /** 判断沙箱内的文件是否存在。相对路径越界或为空会抛异常。 */
    boolean exists(String relativePath);

    /** 读取沙箱内的文件内容。相对路径越界或为空会抛异常。 */
    String read(String relativePath);
}