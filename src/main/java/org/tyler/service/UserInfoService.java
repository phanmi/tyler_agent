package org.tyler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 用户信息读写服务的实现。
 *
 * <p>把「JSON 文件落盘 / 回读 / 字段校验」等所有业务逻辑收敛到这里，
 * 让 controller 只负责 HTTP 层的路由与请求体绑定。
 */
@Service
public class UserInfoService implements IUserInfoService {

    private static final Logger log = LoggerFactory.getLogger(UserInfoService.class);

    /** Gender 的合法取值：空串表示「未选择」。 */
    private static final Set<String> VALID_GENDERS = Set.of("", "男", "女", "其他");

    // 直接 new 一个 ObjectMapper：Spring Boot 4 不再自动注册该 bean，
    // 且 ObjectMapper 本身线程安全、可复用，手动创建最稳、零额外配置依赖。
    // 注意：它只负责「读写 JSON 文件」；请求体的反序列化由 Spring MVC 的 Jackson 3 完成，
    // 因此「Age 必须为整数」这条约束在 save() 里用 BigDecimal 显式校验（见下），
    // 不依赖这里——否则会被 Jackson 的默认行为静默截断（30.5 → 30）。
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path filePath;

    public UserInfoService(@Value("${userinfo.file-path:userinfo.json}") String filePath) {
        // 统一转成绝对路径，保证「读文件」「写文件」「创建父目录」都基于同一稳定位置。
        this.filePath = Path.of(filePath).toAbsolutePath();
    }

    @Override
    public UserInfo get() {
        if (!Files.exists(filePath)) {
            log.info("用户信息文件不存在，返回空结构：{}", filePath);
            return empty();
        }
        try {
            UserInfo info = objectMapper.readValue(Files.readString(filePath), UserInfo.class);
            log.debug("已读取用户信息：{}", filePath);
            return info;
        } catch (IOException e) {
            // 文件被手改坏 / JSON 不合法时，不抛 500，返回空结构兜底。
            log.warn("读取用户信息文件失败，返回空结构：{}", filePath, e);
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
            // 绝对路径必有父目录；这里确保目录存在，避免首次写盘失败。
            Files.createDirectories(filePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), normalized);
            log.info("用户信息已保存到 {}", filePath);
            return normalized;
        } catch (IOException e) {
            log.error("写用户信息文件失败：{}", filePath, e);
            // 抛 IllegalStateException 走 GenericExceptionHandler 的兜底，返回 500。
            throw new IllegalStateException("保存用户信息失败，请稍后重试", e);
        }
    }

    /** 空结构：所有字段留空、Age 为 null。 */
    private static UserInfo empty() {
        return new UserInfo(new User("", "", null, ""), new Other("", ""));
    }
}
