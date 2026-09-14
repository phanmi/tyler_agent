package org.tyler.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.dal.IFoodRecordDAL;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对 {@link FoodController} 做 HTTP 层单元测试（MockMvc + mock DAL）。
 *
 * <p>只验证「路由 + 参数绑定 + 委托 + 响应序列化」，不触碰真实文件读写。
 * 日期缺失 / 空白的 400 依赖 DAL 抛 {@link IllegalArgumentException}，这里用 mock 模拟。
 */
class FoodControllerTest {

    private MockMvc mockMvc;
    private IFoodRecordDAL foodRecordDAL;

    @BeforeEach
    void setUp() {
        foodRecordDAL = mock(IFoodRecordDAL.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FoodController(foodRecordDAL))
                .setControllerAdvice(new GenericExceptionHandler())
                .build();
    }

    @Test
    void foodByDateReturnsFoodArray() throws Exception {
        Food food = new Food(
                new GenericInfo("apple", new BigDecimal("100"), "g", new BigDecimal("200"), "2026-09-12"),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));
        when(foodRecordDAL.getFoodByDate("2026-09-12")).thenReturn(List.of(food));

        mockMvc.perform(get("/api/food").param("date", "2026-09-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].genericInfo.foodName").value("apple"))
                .andExpect(jsonPath("$[0].genericInfo.date").value("2026-09-12"))
                .andExpect(jsonPath("$[0].macroNutrients.protein").value(10));
    }

    @Test
    void missingDateReturnsBadRequest() throws Exception {
        when(foodRecordDAL.getFoodByDate(null))
                .thenThrow(new IllegalArgumentException("日期不能为空"));

        mockMvc.perform(get("/api/food"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("日期不能为空"));
    }
}