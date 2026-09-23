package org.tyler.model.food;

/**
 * 一条已落库食物记录的对外投影：携带数据库主键 {@code id} 与 {@link Food} 本体。
 *
 * <p>用于前端「按主键精确删除单条」：{@code id} 上浮到前端后，删除无需再比较整个
 * {@link Food} 后重写全表，直接 {@code DELETE WHERE id = ?}。
 *
 * <p>刻意不暴露内部字段 {@code createdAt}（那是 {@link FoodRecord} 的落库时间），
 * 只把前端真正需要的「id + 食物内容」交出去。
 *
 * @param id   数据库自增主键（由 SQLite 生成）
 * @param food 食物解析结果
 */
public record FoodEntry(
        long id,
        Food food) {
}
