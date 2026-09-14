package org.tyler.dao;

import org.tyler.model.food.Food;

import java.util.List;

/**
 * 食物记录的最底层文件 IO 契约：负责「整个列表」的读取与落盘，
 * 以及「按日期」的读取与追加保存。
 *
 * <p>{@code load} / {@code save} 提供最朴素的整表读写；{@code loadByDate} /
 * {@code saveByDate} 让日期相关的读/写能以日期为维度直接命中
 * {@link FoodRecordDAOCache} 的日期分桶，避免每次全量扫描。
 */
public interface IFoodRecordDAO {

    /** 读取当前保存的全部食物记录；文件不存在或损坏时返回空列表。 */
    List<Food> load();

    /** 用给定列表整体覆盖保存（覆盖写）。 */
    void save(List<Food> foods);

    /** 返回指定日期（YYYY-MM-DD）下的全部食物记录；日期为空抛 {@link IllegalArgumentException}。 */
    List<Food> loadByDate(String date);

    /** 追加保存一条食物记录到指定日期；日期为空或 food 为空抛 {@link IllegalArgumentException}。 */
    void saveByDate(String date, Food food);
}