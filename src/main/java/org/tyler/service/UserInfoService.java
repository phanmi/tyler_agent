package org.tyler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.filesandbox.FileSandBoxReadAndWrite;
import org.tyler.filesandbox.exceptions.FileWriteException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;

/**
 * 用户信息读写服务的实现。
 *
 * <p>把「JSON 序列化 / 字段校验」等业务逻辑收敛到这里，
 * 但所有文件 IO 都委托给 {@link FileSandBoxReadAndWrite}，本类不再直接触碰磁盘。
 */
@Service
public class UserInfoService implements IUserInfoService {

    private static final Logger log = LoggerFactory.getLogger(UserInfoService.class);

    /** Gender 的合法取值：空串表示「未选择」。 */
    private static final Set<String> VALID_GENDERS = Set.of("", "男", "女", "其他");

    // 直接 new 一个 ObjectMapper：Spring Boot 4 不再自动注册该 bean，
    // 且 ObjectMapper 本身线程安全、可复用，手动创建最稳、零额外配置依赖。
    // 注意：它只负责「JSON 字符串 <-> 对象」的序列化；真正的落盘/回读交给 FileSandbox。
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FileSandBoxReadAndWrite sandbox;
    private final String relativePath;

    public UserInfoService(
            FileSandBoxReadAndWrite sandbox,
            @Value("${userinfo.file-path:userinfo.json}") String relativePath) {
        this.sandbox = sandbox;
        // 这是「沙箱内的相对路径」，而不是绝对路径；实际位置由 FileSandbox 的根目录决定。
        this.relativePath = relativePath;
    }

    @Override
    public UserInfo get() {
        // 先判存在，避免 FileSandbox.read() 对「不存在」抛 FileReadException。
        if (!sandbox.exists(relativePath)) {
            log.info("用户信息文件不存在，返回空结构：{}", relativePath);
            return empty();
        }
        try {
            UserInfo info = objectMapper.readValue(sandbox.read(relativePath), UserInfo.class);
            log.debug("已读取用户信息：{}", relativePath);
            return info;
        } catch (Exception e) {
            // 文件被手改坏 / JSON 不合法 / 读取失败时，不抛 500，返回空结构兜底。
            log.warn("读取用户信息文件失败，返回空结构：{}", relativePath, e);
            return empty();
        }
    }

    @Override
    public UserInfo save(UserInfo userInfo) {
        User user = userInfo.user();
        Other other = userInfo.other();
        if (user == null || other == null) {
            throw new IllegalArgumentException("User 与 Other 不能为空");
        }

        // gender 归一化：null 与空串等价，都视为「未选择」。
        String gender = user.gender() == null ? "" : user.gender();
        if (!VALID_GENDERS.contains(gender)) {
            throw new IllegalArgumentException("gender 只能是 男 / 女 / 其他（或留空）");
        }
        // age：BigDecimal 能精确区分「30」与「30.5」，不会被反序列化静默截断，
        // 因此在这里可靠拦截小数，守住「Age 必须为整数」这条约束。
        BigDecimal age = user.age();
        if (age != null && age.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Age 必须为整数（或留空）");
        }

        // 用归一化后的字段重建，避免把 null gender 写进文件。
        UserInfo normalized = new UserInfo(
                new User(user.name(), gender, user.age(), user.jobType()),
                other);

        try {
            // 先把对象序列化成字符串，再交给 FileSandbox 写盘，本类不直接触碰文件系统。
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
            sandbox.write(relativePath, json);
            log.info("用户信息已保存到 {}", relativePath);
            return normalized;
        } catch (IOException | FileWriteException e) {
            log.error("写用户信息文件失败：{}", relativePath, e);
            // 抛 IllegalStateException 走 GenericExceptionHandler 的兜底，返回 500。
            throw new IllegalStateException("保存用户信息失败，请稍后重试", e);
        }
    }

    /** 空结构：所有字段留空、Age 为 null。 */
    private static UserInfo empty() {
        return new UserInfo(new User("", "", null, ""), new Other("", ""));
    }
}
