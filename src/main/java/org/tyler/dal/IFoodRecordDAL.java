package org.tyler.dal;

import org.tyler.model.food.Food;

import java.util.List;

/**
 * 食物记录「按日期」业务语义的契约。
 *
 * <p>数据持久化细节（JSON / 数据库）由 DAO 层负责，本层只表达
 * 「按日期读 / 按日期存 / 按日期删 / 全量读」的业务语义。
 */
public interface IFoodRecordDAL {

    /** 返回指定日期（YYYY-MM-DD）下的所有食物记录；无记录返回空列表。 */
    List<Food> getFoodByDate(String date);

    /** 追加保存一条食物记录（按其携带的日期归组），返回保存后的这条记录。 */
    Food saveFoodByDate(Food food);

    /** 删除指定日期下的所有食物记录，返回是否真的删除了至少一条。 */
    boolean deleteFoodByDate(String date);

    /** 返回全部食物记录。 */
    List<Food> getAllFoodRecords();

    /** 删除指定日期下第一条与给定 food 结构相等（record equals）的记录，返回是否真的删除了。 */
    boolean deleteFoodFromDate(Food food, String date);
}