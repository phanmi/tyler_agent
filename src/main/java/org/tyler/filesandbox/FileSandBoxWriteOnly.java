package org.tyler.filesandbox;

import org.springframework.stereotype.Component;

/**
 * 只写装饰器：把底层完整沙箱 {@link FileSandBoxReadAndWrite} 包装成「只具备写能力」的组件。
 *
 * <p>本类只暴露 {@code exists} 与 {@code write}，没有 {@code read} 方法，
 * 因此从类型层面就保证了注入方无法读文件。
 */
@Component
public class FileSandBoxWriteOnly implements IFileSandBoxWrite {

    private final FileSandBoxReadAndWrite delegate;

    public FileSandBoxWriteOnly(FileSandBoxReadAndWrite delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean exists(String relativePath) {
        return delegate.exists(relativePath);
    }

    @Override
    public void write(String relativePath, String content) {
        delegate.write(relativePath, content);
    }
}