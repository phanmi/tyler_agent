package org.tyler.service.food;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tyler.dao.foodrecord.FoodRecordDAOCache;
import org.tyler.dao.foodrecord.FoodRecordDAOSqlite;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.controller.food.FoodController;
import org.tyler.tool.foodRecordTool.RecordFoodTool;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies production food DAO selection and persistence after removing legacy storage. */
class FoodRecordSpringWiringTest {

    @TempDir
    Path tempDir;

    @Test
    void springSelectsCacheAndFoodServiceCanSaveReadAndDelete() {
        new ApplicationContextRunner()
                .withUserConfiguration(FileSandbox.class, FoodRecordDAOSqlite.class,
                        FoodRecordDAOCache.class, FoodService.class, FoodController.class, RecordFoodTool.class)
                .withPropertyValues("agent.workspace-dir=" + tempDir,
                        "food.database-path=food-record.sqlite")
                .run(context -> {
                    assertSame(context.getBean(FoodRecordDAOCache.class), context.getBean(IFoodRecordDAO.class));

                    IFoodService service = context.getBean(IFoodService.class);
                    assertSame(context.getBean(FoodService.class), service);
                    FoodController controller = context.getBean(FoodController.class);
                    RecordFoodTool tool = context.getBean(RecordFoodTool.class);
                    assertTrue(service.getAllFoodRecords().isEmpty());
                    String date = "2026-09-27";
                    Food food = new Food(
                            new GenericInfo("Apple", new BigDecimal("100"), "g",
                                    new BigDecimal("52"), date),
                            new MacroNutrients(new BigDecimal("0.3"), new BigDecimal("14"),
                                    new BigDecimal("0.2"), new BigDecimal("2.4")));

                    ObjectMapper mapper = new ObjectMapper();
                    assertEquals(food, mapper.readValue(tool.execute(mapper.writeValueAsString(food)), Food.class));
                    assertEquals(List.of(food), service.getFoodByDate(date));
                    List<FoodEntry> records = controller.foodByDate(date);
                    assertEquals(1, records.size());
                    assertEquals(food, records.getFirst().food());
                    assertTrue(controller.deleteFood(records.getFirst().id()));
                    assertTrue(service.getFoodByDate(date).isEmpty());
                    assertTrue(service.getFoodRecordsByDate(date).isEmpty());
                });
    }
}
