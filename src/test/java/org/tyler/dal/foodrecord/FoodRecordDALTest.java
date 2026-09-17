package org.tyler.dal.foodrecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.dao.foodrecord.FoodRecordDAOSqlite;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对 {@link FoodRecordDAL} 的「按日期」业务语义做单元测试。
 *
 * <p>组合真实的 {@link FoodRecordDAO} + {@link FileSandbox}（落在 {@code @TempDir}），
 * 验证按日期过滤、追加、删除、全量读取的真实读写联动。
 */
class FoodRecordDALTest {

    private static final String DB_FILE = "food-record.sqlite";

    @TempDir
    Path tempDir;

    private FoodRecordDAL newDal() {
        try {
            FileSandbox sandbox = new FileSandbox(tempDir.toString());
            FoodRecordDAOSqlite dao = new FoodRecordDAOSqlite(sandbox, DB_FILE);
            return new FoodRecordDAL(dao);
        } catch (IOException e) {
            throw new RuntimeException("无法创建测试沙箱", e);
        }
    }

    private static Food food(String name, String date) {
        return new Food(
                new GenericInfo(name, new BigDecimal("100"), "g", new BigDecimal("200"), date),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));
    }

    @Test
    void getFoodByDateFiltersByDate() throws IOException {
        FoodRecordDAL dal = newDal();
        dal.saveFoodByDate(food("apple", "2026-09-12"));
        dal.saveFoodByDate(food("banana", "2026-09-12"));
        dal.saveFoodByDate(food("chicken", "2026-09-13"));

        List<Food> result = dal.getFoodByDate("2026-09-12");

        assertEquals(2, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("banana", result.get(1).genericInfo().foodName());
    }

    @Test
    void saveFoodByDateAppends() throws IOException {
        FoodRecordDAL dal = newDal();
        dal.saveFoodByDate(food("apple", "2026-09-12"));
        dal.saveFoodByDate(food("banana", "2026-09-12"));

        assertEquals(2, dal.getAllFoodRecords().size());
        assertEquals(2, dal.getFoodByDate("2026-09-12").size());
    }

    @Test
    void deleteFoodByDateRemovesOnlyThatDate() throws IOException {
        FoodRecordDAL dal = newDal();
        dal.saveFoodByDate(food("apple", "2026-09-12"));
        dal.saveFoodByDate(food("banana", "2026-09-12"));
        dal.saveFoodByDate(food("chicken", "2026-09-13"));

        boolean removed = dal.deleteFoodByDate("2026-09-12");

        assertTrue(removed);
        assertEquals(1, dal.getAllFoodRecords().size());
        assertEquals("chicken", dal.getAllFoodRecords().get(0).genericInfo().foodName());
        assertTrue(dal.getFoodByDate("2026-09-12").isEmpty());
    }

    @Test
    void deleteFoodByDateReturnsFalseWhenNoMatch() throws IOException {
        FoodRecordDAL dal = newDal();
        dal.saveFoodByDate(food("apple", "2026-09-12"));

        boolean removed = dal.deleteFoodByDate("2026-09-13");

        assertFalse(removed);
        assertEquals(1, dal.getAllFoodRecords().size());
    }

    @Test
    void getAllFoodRecordsReturnsEverything() throws IOException {
        FoodRecordDAL dal = newDal();
        dal.saveFoodByDate(food("apple", "2026-09-12"));
        dal.saveFoodByDate(food("chicken", "2026-09-13"));

        assertEquals(2, dal.getAllFoodRecords().size());
    }

    @Test
    void blankDateThrows() throws IOException {
        FoodRecordDAL dal = newDal();

        assertThrows(IllegalArgumentException.class, () -> dal.getFoodByDate("  "));
        assertThrows(IllegalArgumentException.class, () -> dal.getFoodByDate(null));
        assertThrows(IllegalArgumentException.class, () -> dal.deleteFoodByDate("  "));
    }

    @Test
    void deleteFoodFromDateRemovesOnlyMatchingRecord() throws IOException {
        FoodRecordDAL dal = newDal();
        dal.saveFoodByDate(food("apple", "2026-09-12"));
        dal.saveFoodByDate(food("banana", "2026-09-12"));
        dal.saveFoodByDate(food("chicken", "2026-09-13"));

        boolean removed = dal.deleteFoodFromDate(food("banana", "2026-09-12"), "2026-09-12");

        assertTrue(removed);
        assertEquals(2, dal.getAllFoodRecords().size());
        List<Food> remaining = dal.getFoodByDate("2026-09-12");
        assertEquals(1, remaining.size());
        assertEquals("apple", remaining.get(0).genericInfo().foodName());
    }

    @Test
    void deleteFoodFromDateReturnsFalseWhenNoMatch() throws IOException {
        FoodRecordDAL dal = newDal();
        dal.saveFoodByDate(food("apple", "2026-09-12"));

        boolean removed = dal.deleteFoodFromDate(food("banana", "2026-09-12"), "2026-09-12");

        assertFalse(removed);
        assertEquals(1, dal.getAllFoodRecords().size());
    }

    @Test
    void deleteFoodFromDateThrowsOnInvalidArgs() throws IOException {
        FoodRecordDAL dal = newDal();

        assertThrows(IllegalArgumentException.class, () -> dal.deleteFoodFromDate(food("apple", "2026-09-12"), null));
        assertThrows(IllegalArgumentException.class, () -> dal.deleteFoodFromDate(food("apple", "2026-09-12"), "  "));
        assertThrows(IllegalArgumentException.class, () -> dal.deleteFoodFromDate(null, "2026-09-12"));
    }
}