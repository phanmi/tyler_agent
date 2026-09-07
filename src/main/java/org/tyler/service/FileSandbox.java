package org.tyler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把文件读写限制在一个沙箱工作目录内，防止路径穿越访问目录之外的文件。
 *
 * <p>根目录默认是 {@code {user.home}/AppData/Local/tyler_agent}，
 * 可通过 {@code agent.workspace-dir}（或环境变量 {@code AGENT_WORKSPACE_DIR}）覆盖。
 */
@Component
public class FileSandbox {

    private static final Logger log = LoggerFactory.getLogger(FileSandbox.class);

    private final Path root;

    public FileSandbox(@Value("${agent.workspace-dir:}") String workspaceDir) throws IOException {
        this.root = resolveRoot(workspaceDir);
        Files.createDirectories(root);
    }

    private static Path resolveRoot(String workspaceDir) {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Path.of(workspaceDir).toAbsolutePath().normalize();
        }
        String home = System.getProperty("user.home");
        return Path.of(home, "AppData", "Local", "tyler_agent").toAbsolutePath().normalize();
    }

    /** 返回沙箱根目录的绝对路径。 */
    public Path root() {
        return root;
    }

    /** 读取沙箱内的文件内容。相对路径越界或为空会抛异常。 */
    public String read(String relativePath) {
        log.debug("读取文件：{}", relativePath);
        try {
            return Files.readString(resolveInside(relativePath));
        } catch (IOException e) {
            throw new FileReadException(e.getMessage(), e);
        }
    }

    /** 把内容写入沙箱内的文件，自动创建父目录。 */
    public void write(String relativePath, String content) {
        log.debug("写入文件：{}（{} 字符）", relativePath, content == null ? 0 : content.length());
        Path target = resolveInside(relativePath);
        Path parent = target.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new FileWriteException(e.getMessage(), e);
            }
        }
        try {
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new FileWriteException(e.getMessage(), e);
        }
    }

    /** 把相对路径解析到沙箱内，并校验没有越界。 */
    private Path resolveInside(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("路径越出工作区：" + relativePath);
        }
        return resolved;
    }
}