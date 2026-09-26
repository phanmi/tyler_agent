package org.tyler.controller.food;

import org.tyler.model.food.FoodEntry;

import java.util.List;

/**
 * 食物记录相关的 REST 契约。
 *
 * <p>只声明 HTTP 接口的方法签名；数据模型（{@link FoodEntry} 等）已下沉到
 * {@link org.tyler.model.food} 包。
 */
public interface IFoodController {

    /** 按日期（YYYY-MM-DD）查询当天全部食物记录（带主键 id）；无记录返回空列表。 */
    List<FoodEntry> foodByDate(String date);

    /** 按主键删除单条食物记录，返回是否真的删除了一条。 */
    boolean deleteFood(long id);

    /** 删除指定日期（YYYY-MM-DD）下的全部食物记录，返回是否真的删除了至少一条。 */
    boolean deleteFoodByDate(String date);
}