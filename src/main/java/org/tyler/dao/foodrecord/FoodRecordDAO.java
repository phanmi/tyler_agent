package org.tyler.dao.foodrecord;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 食物记录的最底层文件 IO 实现。
 *
 * <p>只负责「JSON 序列化 + 通过 {@link IFileSandboxRead}/{@link IFileSandboxWrite} 落盘/回读」，
 * 与 {@link org.tyler.service.userInfo.UserInfoService} 同一套范式，本类不直接触碰磁盘。
 *
 * <p>TODO: 当前临时以 JSON 文件（{@code food-records.json}）承载；计划后续用
 * SQLite 数据库替换本实现，对外契约 {@link IFoodRecordDAO} 保持不变。
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

    @Override
    public List<Food> loadByDate(String date) {
        requireDate(date);
        List<Food> result = new ArrayList<>();
        for (Food food : load()) {
            if (food != null && date.equals(dateOf(food))) {
                result.add(food);
            }
        }
        return result;
    }

    @Override
    public void saveByDate(String date, Food food) {
        requireDate(date);
        if (food == null) {
            throw new IllegalArgumentException("Food 不能为空");
        }
        // 追加语义：同一天可以吃多餐，覆盖会丢数据，所以读出现有列表后追加再写回。
        List<Food> foods = new ArrayList<>(load());
        foods.add(food);
        save(foods);
    }

    @Override
    public List<FoodRecord> loadRecordsByDate(String date) {
        throw new UnsupportedOperationException("JSON DAO 不支持返回带主键的记录");
    }

    @Override
    public boolean deleteByDate(String date) {
        throw new UnsupportedOperationException("JSON DAO 不支持按日期删除");
    }

    @Override
    public boolean deleteById(long id) {
        throw new UnsupportedOperationException("JSON DAO 不支持按主键删除");
    }

    private static String dateOf(Food food) {
        return food.genericInfo() == null ? null : food.genericInfo().date();
    }

    private static void requireDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("日期不能为空");
        }
    }
}