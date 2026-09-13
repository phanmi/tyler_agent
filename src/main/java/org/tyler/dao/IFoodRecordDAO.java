package org.tyler.dao;

import org.tyler.model.food.Food;

import java.util.List;

/**
 * 食物记录的最底层文件 IO 契约：只负责「整个列表」的读取与落盘，
 * 不感知任何「按日期」之类的业务语义。
 *
 * <p>业务层的日期语义（按日期读 / 存 / 删）由 {@link org.tyler.dal.IFoodRecordDAL} 表达，
 * 本接口只提供最朴素的 {@code load} / {@code save}。
 */
public interface IFoodRecordDAO {

    /** 读取当前保存的全部食物记录；文件不存在或损坏时返回空列表。 */
    List<Food> load();

    /** 用给定列表整体覆盖保存（覆盖写）。 */
    void save(List<Food> foods);
}