package org.tyler.dao;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.filesandbox.exceptions.FileWriteException;
import org.tyler.model.food.Food;

import java.io.IOException;
import java.util.List;

/**
 * 食物记录的最底层文件 IO 实现。
 *
 * <p>只负责「JSON 序列化 + 通过 {@link IFileSandboxRead}/{@link IFileSandboxWrite} 落盘/回读」，
 * 与 {@link org.tyler.service.UserInfoService} 同一套范式，本类不直接触碰磁盘。
 *
 * <p>TODO: 当前临时以 JSON 文件（{@code food-records.json}）承载；正式存储方案
 * （如数据库）待拍板后替换本实现，对外契约 {@link IFoodRecordDAO} 保持不变。
 */
@Repository
public class FoodRecordDAO implements IFoodRecordDAO {

    private static final Logger log = LoggerFactory.getLogger(FoodRecordDAO.class);

    // 直接 new 一个 ObjectMapper：线程安全、可复用，只负责「JSON 字符串 <-> 对象」序列化，
    // 真正的落盘/回读交给 FileSandbox。
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final String relativePath;

    public FoodRecordDAO(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${food.file-path:food-records.json}") String relativePath) {
        this.reader = reader;
        this.writer = writer;
        // 沙箱内的相对路径，实际位置由 FileSandbox 的根目录决定。
        this.relativePath = relativePath;
    }

    @Override
    public List<Food> load() {
        // 先判存在，避免 reader.read() 对「不存在」抛 FileReadException。
        if (!reader.exists(relativePath)) {
            return List.of();
        }
        try {
            List<Food> foods = objectMapper.readValue(
                    reader.read(relativePath),
                    new TypeReference<List<Food>>() {});
            log.debug("已读取食物记录：{}", relativePath);
            return foods == null ? List.of() : foods;
        } catch (Exception e) {
            // 文件被手改坏 / JSON 不合法 / 读取失败时，不抛异常，返回空列表兜底。
            log.warn("读取食物记录文件失败，返回空列表：{}", relativePath, e);
            return List.of();
        }
    }

    @Override
    public void save(List<Food> foods) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(foods);
            writer.write(relativePath, json);
            log.debug("食物记录已写入 {}（{} 条）", relativePath, foods == null ? 0 : foods.size());
        } catch (IOException | FileWriteException e) {
            log.error("写食物记录文件失败：{}", relativePath, e);
            // 抛 IllegalStateException 走 GenericExceptionHandler 的兜底，返回 500。
            throw new IllegalStateException("保存食物记录失败，请稍后重试", e);
        }
    }
}