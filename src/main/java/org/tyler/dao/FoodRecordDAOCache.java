package org.tyler.dao;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.tyler.model.food.Food;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食物记录 DAO 的内存缓存装饰器，实现 {@link IFoodRecordDAO}（与 {@link FoodRecordDAO} 共用同一接口）。
 *
 * <p>缓存内部按日期分桶为 {@code Map<String, List<Food>>}，因为多数读取都以日期为维度。
 * 读（{@code load}）：命中直接展平返回，不再重复读文件；未命中则走 {@link FoodRecordDAO}
 * 读文件、分桶回填后返回。
 * 写（{@code save}）：写穿——先落盘成功，再按传入列表重新分桶、整体替换缓存，避免落盘失败时缓存变脏。
 *
 * <p>日期取值来自 {@code food.genericInfo().date()}；日期为空的记录归入 {@code null} 桶，
 * 用 {@link LinkedHashMap} 保序并允许 {@code null} key，确保不丢数据。
 *
 * <p>单例 bean，{@code load}/{@code save} 用 {@code synchronized} 保证线程安全，
 * 对外返回防御性拷贝，避免外部改动污染缓存。
 */
@Primary
@Component
public class FoodRecordDAOCache implements IFoodRecordDAO {

    private final FoodRecordDAO delegate;

    /** 按日期分桶的缓存；volatile 保证跨线程可见性。 */
    private volatile Map<String, List<Food>> cached;

    public FoodRecordDAOCache(FoodRecordDAO delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized List<Food> load() {
        if (cached != null) {
            return flatten(cached);
        }
        List<Food> loaded = delegate.load();
        cached = bucketByDate(loaded);
        return flatten(cached);
    }

    @Override
    public synchronized void save(List<Food> foods) {
        // 先落盘成功，再按传入列表重新分桶、整体替换缓存；失败时缓存保持旧值、异常照常上抛。
        delegate.save(foods);
        cached = bucketByDate(foods);
    }

    private static String dateOf(Food food) {
        return food.genericInfo() == null ? null : food.genericInfo().date();
    }

    /** 将列表按日期分桶；LinkedHashMap 保序并允许 null key。 */
    private static Map<String, List<Food>> bucketByDate(List<Food> foods) {
        Map<String, List<Food>> buckets = new LinkedHashMap<>();
        if (foods != null) {
            for (Food food : foods) {
                buckets.computeIfAbsent(dateOf(food), k -> new ArrayList<>()).add(food);
            }
        }
        return buckets;
    }

    /** 将分桶后的缓存展平为列表（每次新建，天然防御性拷贝）。 */
    private static List<Food> flatten(Map<String, List<Food>> buckets) {
        List<Food> result = new ArrayList<>();
        for (List<Food> bucket : buckets.values()) {
            result.addAll(bucket);
        }
        return result;
    }
}
