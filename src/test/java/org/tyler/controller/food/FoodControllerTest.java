package org.tyler.controller.food;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.service.food.IFoodService;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP tests for {@link FoodController} with MockMvc and a mocked service.
 *
 * <p>Verifies routes, parameter binding, delegation, and response serialization.
 * The mocked service simulates {@link IllegalArgumentException} for missing or blank dates.
 */
class FoodControllerTest {

    private MockMvc mockMvc;
    private IFoodService foodService;

    @BeforeEach
    void setUp() {
        foodService = mock(IFoodService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FoodController(foodService))
                .setControllerAdvice(new GenericExceptionHandler())
                .build();
    }

    @Test
    void foodByDateReturnsFoodEntryArray() throws Exception {
        Food food = new Food(
                new GenericInfo("apple", new BigDecimal("100"), "g", new BigDecimal("200"), "2026-09-12"),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));
        when(foodService.getFoodRecordsByDate("2026-09-12")).thenReturn(List.of(new FoodEntry(7L, food)));

        mockMvc.perform(get("/api/food").param("date", "2026-09-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].food.genericInfo.foodName").value("apple"))
                .andExpect(jsonPath("$[0].food.genericInfo.date").value("2026-09-12"))
                .andExpect(jsonPath("$[0].food.macroNutrients.protein").value(10));
    }

    @Test
    void missingDateReturnsBadRequest() throws Exception {
        when(foodService.getFoodRecordsByDate(null))
                .thenThrow(new IllegalArgumentException("Date must not be blank"));

        mockMvc.perform(get("/api/food"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Date must not be blank"));
    }

    @Test
    void deleteFoodDelegatesToService() throws Exception {
        when(foodService.deleteFoodById(7L)).thenReturn(true);

        mockMvc.perform(delete("/api/food/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));
    }

    @Test
    void deleteFoodByDateDelegatesToService() throws Exception {
        when(foodService.deleteFoodByDate("2026-09-12")).thenReturn(true);

        mockMvc.perform(delete("/api/food/date/2026-09-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(true));
    }
}
