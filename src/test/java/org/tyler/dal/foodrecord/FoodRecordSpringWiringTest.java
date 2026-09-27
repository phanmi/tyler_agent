package org.tyler.dal.foodrecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tyler.dao.foodrecord.FoodRecordDAOCache;
import org.tyler.dao.foodrecord.FoodRecordDAOSqlite;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.filesandbox.FileSandbox;
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
    void springSelectsSqliteAndFoodDalCanSaveReadAndDelete() {
        new ApplicationContextRunner()
                .withUserConfiguration(FileSandbox.class, FoodRecordDAOSqlite.class,
                        FoodRecordDAOCache.class, FoodRecordDAL.class)
                .withPropertyValues("agent.workspace-dir=" + tempDir,
                        "food.database-path=food-record.sqlite")
                .run(context -> {
                    assertSame(context.getBean(FoodRecordDAOSqlite.class), context.getBean(IFoodRecordDAO.class));

                    FoodRecordDAL dal = context.getBean(FoodRecordDAL.class);
                    String date = "2026-09-27";
                    Food food = new Food(
                            new GenericInfo("Apple", new BigDecimal("100"), "g",
                                    new BigDecimal("52"), date),
                            new MacroNutrients(new BigDecimal("0.3"), new BigDecimal("14"),
                                    new BigDecimal("0.2"), new BigDecimal("2.4")));

                    dal.saveFoodByDate(food);
                    List<FoodEntry> records = dal.getFoodRecordsByDate(date);
                    assertEquals(1, records.size());
                    assertEquals(food, records.getFirst().food());
                    assertTrue(dal.deleteFoodById(records.getFirst().id()));
                    assertTrue(dal.getFoodRecordsByDate(date).isEmpty());
                });
    }
}
