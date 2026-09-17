package org.tyler.model.food;

import java.time.LocalDateTime;

/**
 * 一条已落库的食物记录：数据库行的完整投影。
 *
 * <p>与 {@link Food}（纯解析结果）的区别在于它携带数据库自增主键 {@code id}
 * 与写入时间 {@code createdAt}，让「按 id 精确删除单条」成为可能，而无需
 * 比较整个 {@link Food} 后再重写全表。
 *
 * @param id        数据库自增主键（由 SQLite 生成）
 * @param food      食物解析结果
 * @param createdAt 写入时间
 */
public record FoodRecord(
        long id,
        Food food,
        LocalDateTime createdAt) {
}