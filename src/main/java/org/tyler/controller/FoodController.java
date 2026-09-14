package org.tyler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.dal.IFoodRecordDAL;
import org.tyler.model.food.Food;

import java.util.List;

/**
 * 食物记录相关的 REST 控制器（薄控制器）。
 *
 * <p>只负责 HTTP 层：路由映射 + 查询参数绑定 + 委托给 {@link IFoodRecordDAL}。
 * 日期校验不在本层做——{@code date} 用 {@code required = false} 接收，让缺失 / 空白
 * 都流入 DAL 的 {@code requireDate}，由其抛 {@link IllegalArgumentException}，
 * 再由 {@link org.tyler.exceptionHandler.GenericExceptionHandler} 统一映射为 400。
 */
@RestController
@RequestMapping("/api/food")
public class FoodController implements IFoodController {

    private final IFoodRecordDAL foodRecordDAL;

    public FoodController(IFoodRecordDAL foodRecordDAL) {
        this.foodRecordDAL = foodRecordDAL;
    }

    @Override
    @GetMapping
    public List<Food> foodByDate(@RequestParam(value = "date", required = false) String date) {
        return foodRecordDAL.getFoodByDate(date);
    }
}