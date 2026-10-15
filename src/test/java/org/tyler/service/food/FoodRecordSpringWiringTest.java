package org.tyler.service.food;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Verifies the discovered food DAO, its actual service injection, and consistent read/write paths. */
class FoodRecordSpringWiringTest {

    @TempDir
    Path tempDir;

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = FoodRecordDAOSqlite.class)
    static class FoodDaoScan {
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(FileSandbox.class, FoodDaoScan.class,
                        FoodService.class, FoodController.class, RecordFoodTool.class)
                .withPropertyValues("agent.workspace-dir=" + tempDir,
                        "food.database-path=food-record.sqlite");
    }

    @Test
    void springInjectsTheOnlySqliteDaoAndAdaptersShareItsRecords() {
        context().run(context -> {
                    assertEquals(1, context.getBeansOfType(IFoodRecordDAO.class).size());
                    IFoodRecordDAO dao = context.getBean(IFoodRecordDAO.class);
                    assertSame(context.getBean(FoodRecordDAOSqlite.class), dao);

                    IFoodService service = context.getBean(IFoodService.class);
                    assertSame(context.getBean(FoodService.class), service);
                    assertSame(dao, ReflectionTestUtils.getField(service, "dao"));
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

    @Test
    void appendsAndDeletionsAreImmediatelyVisibleThroughEveryReadPath() {
        context().run(context -> {
            IFoodRecordDAO dao = context.getBean(IFoodRecordDAO.class);
            IFoodService service = context.getBean(IFoodService.class);
            FoodController controller = context.getBean(FoodController.class);
            String date = "2026-10-14";
            String otherDate = "2026-10-15";
            Food first = food("Apple", date);
            Food second = food("Banana", date);
            Food other = food("Pear", otherDate);

            dao.save(List.of(first));
            assertReadPaths(dao, service, controller, date, List.of(first), List.of(first));
            long firstId = dao.loadRecordsByDate(date).getFirst().id();
            // Read before each append to expose any stale/replaced in-memory state.
            dao.save(List.of(second));
            assertReadPaths(dao, service, controller, date, List.of(first, second), List.of(first, second));
            assertEquals(firstId, dao.loadRecordsByDate(date).getFirst().id());
            service.saveFoodByDate(other);
            assertReadPaths(dao, service, controller, date, List.of(first, second), List.of(first, second, other));
            assertReadPaths(dao, service, controller, otherDate, List.of(other), List.of(first, second, other));

            assertTrue(controller.deleteFood(firstId));
            assertReadPaths(dao, service, controller, date, List.of(second), List.of(second, other));
            assertReadPaths(dao, service, controller, otherDate, List.of(other), List.of(second, other));
            assertFalse(controller.deleteFood(firstId));
            assertTrue(controller.deleteFoodByDate(date));
            assertReadPaths(dao, service, controller, date, List.of(), List.of(other));
            assertReadPaths(dao, service, controller, otherDate, List.of(other), List.of(other));
            assertFalse(controller.deleteFoodByDate(date));
        });
    }

    private static Food food(String name, String date) {
        return new Food(new GenericInfo(name, BigDecimal.ONE, "piece", BigDecimal.TEN, date),
                new MacroNutrients(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    }

    private static void assertReadPaths(IFoodRecordDAO dao, IFoodService service, FoodController controller,
                                       String date, List<Food> expectedDate, List<Food> expectedAll) {
        assertEquals(expectedAll, dao.load());
        assertEquals(expectedAll, service.getAllFoodRecords());
        assertEquals(expectedDate, dao.loadByDate(date));
        assertEquals(expectedDate, service.getFoodByDate(date));
        var persisted = dao.loadRecordsByDate(date);
        assertEquals(expectedDate, persisted.stream().map(record -> record.food()).toList());
        List<FoodEntry> entries = persisted.stream().map(record -> new FoodEntry(record.id(), record.food())).toList();
        assertEquals(entries, service.getFoodRecordsByDate(date));
        assertEquals(entries, controller.foodByDate(date));
    }
}
