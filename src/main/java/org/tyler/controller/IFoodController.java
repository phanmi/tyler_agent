package org.tyler.controller;

import org.tyler.model.food.Food;

import java.util.List;

/**
 * 食物记录相关的 REST 契约。
 *
 * <p>只声明 HTTP 接口的方法签名；数据模型（{@link Food} 等）已下沉到
 * {@link org.tyler.model.food} 包。
 */
public interface IFoodController {

    /** 按日期（YYYY-MM-DD）查询当天的全部食物记录；无记录返回空列表。 */
    List<Food> foodByDate(String date);
}