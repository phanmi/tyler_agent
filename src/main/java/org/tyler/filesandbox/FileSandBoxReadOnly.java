package org.tyler.filesandbox;

import org.springframework.stereotype.Component;

/**
 * 只读装饰器：把底层完整沙箱 {@link FileSandBoxReadAndWrite} 包装成「只具备读能力」的组件。
 *
 * <p>本类只暴露 {@code exists} 与 {@code read}，没有 {@code write} 方法，
 * 因此从类型层面就保证了注入方无法写文件。
 */
@Component
public class FileSandBoxReadOnly implements IFileSandBoxRead {

    private final FileSandBoxReadAndWrite delegate;

    public FileSandBoxReadOnly(FileSandBoxReadAndWrite delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean exists(String relativePath) {
        return delegate.exists(relativePath);
    }

    @Override
    public String read(String relativePath) {
        return delegate.read(relativePath);
    }
}