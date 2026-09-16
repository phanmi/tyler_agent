package org.tyler.dao.foodrecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对 {@link FoodRecordDAOSqlite} 的核心 SQL 语义做单元测试。
 *
 * <p>每个用例用独立的 {@code @TempDir} 作为沙箱根目录（真实 {@link FileSandbox}），
 * 真实落 SQLite 文件，覆盖「空表读 / 覆盖写 / 按日期过滤 / 追加 / 参数校验」。
 */
class FoodRecordDAOSqliteTest {

    @TempDir
    Path tempDir;

    private FoodRecordDAOSqlite newDao() throws Exception {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        return new FoodRecordDAOSqlite(sandbox);
    }

    private static Food food(String name, String date) {
        return new Food(
                new GenericInfo(name, new BigDecimal("100"), "g", new BigDecimal("200"), date),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));
    }

    @Test
    void loadReturnsEmptyWhenTableEmpty() throws Exception {
        FoodRecordDAOSqlite dao = newDao();

        assertTrue(dao.load().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12"), food("banana", "2026-09-12")));

        List<Food> loaded = dao.load();

        assertEquals(2, loaded.size());
        assertEquals("apple", loaded.get(0).genericInfo().foodName());
        assertEquals("banana", loaded.get(1).genericInfo().foodName());
        assertEquals("2026-09-12", loaded.get(0).genericInfo().date());
        assertEquals(new BigDecimal("200"), loaded.get(0).genericInfo().calories());
        assertEquals(new BigDecimal("10"), loaded.get(0).macroNutrients().protein());
    }

    @Test
    void saveOverwritesExistingData() throws Exception {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12")));
        dao.save(List.of(food("banana", "2026-09-13")));

        List<Food> loaded = dao.load();

        assertEquals(1, loaded.size());
        assertEquals("banana", loaded.get(0).genericInfo().foodName());
    }

    @Test
    void loadByDateFiltersByDate() throws Exception {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(
                food("apple", "2026-09-12"),
                food("banana", "2026-09-12"),
                food("chicken", "2026-09-13")));

        List<Food> result = dao.loadByDate("2026-09-12");

        assertEquals(2, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("banana", result.get(1).genericInfo().foodName());
    }

    @Test
    void loadByDateReturnsEmptyWhenNoMatch() throws Exception {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12")));

        assertTrue(dao.loadByDate("2026-09-13").isEmpty());
    }

    @Test
    void saveByDateAppends() throws Exception {
        FoodRecordDAOSqlite dao = newDao();
        dao.saveByDate("2026-09-12", food("apple", "2026-09-12"));
        dao.saveByDate("2026-09-12", food("banana", "2026-09-12"));

        assertEquals(2, dao.load().size());
        assertEquals(2, dao.loadByDate("2026-09-12").size());
    }

    @Test
    void loadByDateThrowsOnBlankDate() throws Exception {
        FoodRecordDAOSqlite dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.loadByDate(null));
        assertThrows(IllegalArgumentException.class, () -> dao.loadByDate("  "));
    }

    @Test
    void saveByDateThrowsOnInvalidArgs() throws Exception {
        FoodRecordDAOSqlite dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate(null, food("apple", "2026-09-12")));
        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate("  ", food("apple", "2026-09-12")));
        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate("2026-09-12", null));
    }
}
