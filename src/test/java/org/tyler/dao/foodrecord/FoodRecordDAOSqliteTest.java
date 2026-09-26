package org.tyler.dao.foodrecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests SQL behavior in {@link FoodRecordDAOSqlite}.
 *
 * <p>Each test uses a real {@link FileSandbox} rooted in {@code @TempDir}.
 * Real SQLite files cover empty reads, appends, filtering, deletion by date or ID,
 * records with IDs, validation, and null rejection.
 */
class FoodRecordDAOSqliteTest {

    private static final String DB_FILE = "food-record.sqlite";

    @TempDir
    Path tempDir;

    private FoodRecordDAOSqlite newDao() {
        try {
            FileSandbox sandbox = new FileSandbox(tempDir.toString());
            return new FoodRecordDAOSqlite(sandbox, DB_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create the test sandbox", e);
        }
    }

    private static Food food(String name, String date) {
        return new Food(
                new GenericInfo(name, new BigDecimal("100"), "g", new BigDecimal("200"), date),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));
    }

    private void makeDatabaseUnavailable() throws IOException {
        Path dbPath = tempDir.resolve(DB_FILE);
        Files.delete(dbPath);
        Files.createDirectory(dbPath);
    }

    @Test
    void databaseReadFailuresUseReadException() throws IOException {
        FoodRecordDAOSqlite dao = newDao();
        makeDatabaseUnavailable();

        assertInstanceOf(DataAccessException.class, assertThrows(SQLReadException.class, dao::load).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLReadException.class, () -> dao.loadByDate("2026-09-12")).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLReadException.class, () -> dao.loadRecordsByDate("2026-09-12")).getCause());
    }

    @Test
    void databaseWriteFailuresUsePersistentException() throws IOException {
        FoodRecordDAOSqlite dao = newDao();
        makeDatabaseUnavailable();
        Food sample = food("apple", "2026-09-12");

        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class, () -> dao.save(List.of(sample))).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class, () -> dao.saveByDate("2026-09-12", sample)).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class, () -> dao.deleteByDate("2026-09-12")).getCause());
        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class, () -> dao.deleteById(1)).getCause());
    }

    @Test
    void schemaFailureUsesPersistentException() throws IOException {
        Files.createDirectory(tempDir.resolve(DB_FILE));
        FileSandbox sandbox = new FileSandbox(tempDir.toString());

        assertInstanceOf(DataAccessException.class,
                assertThrows(SQLPersistentException.class,
                        () -> new FoodRecordDAOSqlite(sandbox, DB_FILE)).getCause());
    }

    @Test
    void loadReturnsEmptyWhenTableEmpty() {
        FoodRecordDAOSqlite dao = newDao();

        assertTrue(dao.load().isEmpty());
    }

    @Test
    void saveIsPureInsert() {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12")));
        dao.save(List.of(food("banana", "2026-09-13")));

        List<Food> loaded = dao.load();

        // Both saves append records, preserving earlier entries.
        assertEquals(2, loaded.size());
        assertEquals("apple", loaded.get(0).genericInfo().foodName());
        assertEquals("banana", loaded.get(1).genericInfo().foodName());
    }

    @Test
    void saveAndLoadRoundTrip() {
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
    void loadByDateFiltersByDate() {
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
    void loadByDateReturnsEmptyWhenNoMatch() {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12")));

        assertTrue(dao.loadByDate("2026-09-13").isEmpty());
    }

    @Test
    void saveByDateAppends() {
        FoodRecordDAOSqlite dao = newDao();
        dao.saveByDate("2026-09-12", food("apple", "2026-09-12"));
        dao.saveByDate("2026-09-12", food("banana", "2026-09-12"));

        assertEquals(2, dao.load().size());
        assertEquals(2, dao.loadByDate("2026-09-12").size());
    }

    @Test
    void loadRecordsByDateReturnsIdAndCreatedAt() {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12")));

        List<FoodRecord> records = dao.loadRecordsByDate("2026-09-12");

        assertEquals(1, records.size());
        assertTrue(records.get(0).id() > 0);
        assertEquals("apple", records.get(0).food().genericInfo().foodName());
        assertNotNull(records.get(0).createdAt());
    }

    @Test
    void deleteByDateRemovesOnlyThatDate() {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(
                food("apple", "2026-09-12"),
                food("banana", "2026-09-12"),
                food("chicken", "2026-09-13")));

        boolean removed = dao.deleteByDate("2026-09-12");

        assertTrue(removed);
        List<Food> remaining = dao.load();
        assertEquals(1, remaining.size());
        assertEquals("chicken", remaining.get(0).genericInfo().foodName());
    }

    @Test
    void deleteByDateReturnsFalseWhenNoMatch() {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12")));

        assertFalse(dao.deleteByDate("2026-09-13"));
        assertEquals(1, dao.load().size());
    }

    @Test
    void deleteByIdRemovesSingleRecord() {
        FoodRecordDAOSqlite dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12"), food("banana", "2026-09-12")));

        List<FoodRecord> records = dao.loadRecordsByDate("2026-09-12");
        long appleId = records.get(0).id();

        boolean removed = dao.deleteById(appleId);

        assertTrue(removed);
        List<Food> remaining = dao.load();
        assertEquals(1, remaining.size());
        assertEquals("banana", remaining.get(0).genericInfo().foodName());
    }

    @Test
    void deleteByIdReturnsFalseWhenNotFound() {
        FoodRecordDAOSqlite dao = newDao();

        assertFalse(dao.deleteById(999));
    }

    @Test
    void loadByDateThrowsOnBlankDate() {
        FoodRecordDAOSqlite dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.loadByDate(null));
        assertThrows(IllegalArgumentException.class, () -> dao.loadByDate("  "));
    }

    @Test
    void saveByDateThrowsOnInvalidArgs() {
        FoodRecordDAOSqlite dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate(null, food("apple", "2026-09-12")));
        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate("  ", food("apple", "2026-09-12")));
        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate("2026-09-12", null));
    }

    @Test
    void deleteByDateThrowsOnBlankDate() {
        FoodRecordDAOSqlite dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.deleteByDate(null));
        assertThrows(IllegalArgumentException.class, () -> dao.deleteByDate("  "));
    }

    @Test
    void saveRejectsNullFields() {
        FoodRecordDAOSqlite dao = newDao();

        Food invalid = new Food(
                new GenericInfo("apple", null, "g", new BigDecimal("200"), "2026-09-12"),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));

        assertThrows(SQLDataValidationException.class, () -> dao.save(List.of(invalid)));
    }
}
