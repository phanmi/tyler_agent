package org.tyler.dao.foodrecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite tests for stable identity, embedded dates, restart compatibility, and errors. */
class FoodRecordDAOSqliteTest {
    @TempDir Path tempDir;
    private static final String DATE = "2026-09-12";
    private FoodRecordDAOSqlite newDao() throws Exception {
        return new FoodRecordDAOSqlite(new FileSandbox(tempDir.toString()), "food.sqlite");
    }
    private static Food food(String name, String date) {
        return new Food(new GenericInfo(name, new BigDecimal("100.25"), "g", new BigDecimal("200.5"), date),
                new MacroNutrients(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, new BigDecimal("2.3")));
    }
    @Test
    void identicalEntriesHaveIndependentIdsAndSurviveReopening() throws Exception {
        var dao = newDao();
        Food duplicate = food("apple", DATE);
        long first = dao.insert(duplicate);
        var original = dao.loadRecordsByDate(DATE).getFirst();
        long second = dao.insert(duplicate);
        assertTrue(first > 0);
        assertTrue(second > first);
        var reopened = newDao();
        var records = reopened.loadRecordsByDate(DATE);
        assertEquals(List.of(first, second), records.stream().map(r -> r.id()).toList());
        assertEquals(List.of(duplicate, duplicate), records.stream().map(r -> r.food()).toList());
        assertEquals(original, records.getFirst());
        assertNotNull(original.createdAt());
        assertTrue(reopened.deleteById(second));
        assertEquals(List.of(original), reopened.loadRecordsByDate(DATE));
        assertFalse(reopened.deleteById(second));
    }
    @Test
    void embeddedDateControlsLookupAndDateDeletion() throws Exception {
        var dao = newDao();
        assertTrue(dao.loadRecordsByDate(DATE).isEmpty());
        Food first = food("apple", DATE);
        Food second = food("banana", DATE);
        Food other = food("pear", "2026-09-13");
        long firstId = dao.insert(first);
        long secondId = dao.insert(second);
        long otherId = dao.insert(other);
        assertEquals(List.of(firstId, secondId), dao.loadRecordsByDate(DATE).stream().map(r -> r.id()).toList());
        assertEquals(other, dao.loadRecordsByDate("2026-09-13").getFirst().food());
        assertTrue(dao.deleteByDate(DATE));
        assertTrue(dao.loadRecordsByDate(DATE).isEmpty());
        assertFalse(dao.deleteByDate(DATE));
        assertEquals(otherId, dao.loadRecordsByDate("2026-09-13").getFirst().id());
        assertFalse(dao.deleteById(9999));
    }
    @Test
    void rejectsInvalidInputsWithoutChangingExistingData() throws Exception {
        var dao = newDao();
        dao.insert(food("apple", DATE));
        var before = dao.loadRecordsByDate(DATE);
        assertThrows(IllegalArgumentException.class, () -> dao.insert(null));
        assertThrows(IllegalArgumentException.class, () -> dao.insert(food("apple", null)));
        assertThrows(IllegalArgumentException.class, () -> dao.insert(food("apple", "  ")));
        assertThrows(IllegalArgumentException.class, () -> dao.loadRecordsByDate(null));
        assertThrows(IllegalArgumentException.class, () -> dao.loadRecordsByDate(" "));
        assertThrows(IllegalArgumentException.class, () -> dao.deleteByDate(null));
        assertThrows(IllegalArgumentException.class, () -> dao.deleteByDate(" "));
        assertThrows(SQLDataValidationException.class, () -> dao.insert(new Food(food("apple", DATE).genericInfo(), null)));
        assertThrows(SQLDataValidationException.class, () -> dao.insert(new Food(
                new GenericInfo("apple", null, "g", BigDecimal.ONE, DATE), food("apple", DATE).macroNutrients())));
        assertEquals(before, dao.loadRecordsByDate(DATE));
    }
    @Test
    void missingGenericInfoRaisesValidationExceptionWithoutChangingExistingData() throws Exception {
        var dao = newDao();
        Food validFood = food("apple", DATE);
        dao.insert(validFood);
        var before = dao.loadRecordsByDate(DATE);

        var exception = assertThrowsExactly(SQLDataValidationException.class,
                () -> dao.insert(new Food(null, validFood.macroNutrients())));

        assertEquals("Food record is missing genericInfo", exception.getMessage());
        assertEquals(before, dao.loadRecordsByDate(DATE));
    }

    @Test
    void existingDatabaseRowsKeepTheirIdsDatesAndCreationTimes() throws Exception {
        // Seed the unchanged table using the previous non-returning insertion format.
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("food.sqlite"));
             var statement = connection.createStatement();
             var schema = getClass().getClassLoader().getResourceAsStream("db/food_record/create_table.sql");
             var fixture = getClass().getClassLoader().getResourceAsStream("db/food_record/existing_record.sql")) {
            statement.execute(new String(schema.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            statement.execute(new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var dao = newDao();
        var original = dao.loadRecordsByDate(DATE).getFirst();
        assertEquals(42L, original.id());
        assertEquals("existing apple", original.food().genericInfo().foodName());
        assertEquals(java.time.LocalDateTime.parse("2026-09-12T12:00:00"), original.createdAt());
        long newId = dao.insert(food("banana", DATE));
        assertTrue(newId > original.id());
        assertEquals(original, newDao().loadRecordsByDate(DATE).getFirst());
        assertTrue(dao.deleteById(newId));
        assertEquals(List.of(original), dao.loadRecordsByDate(DATE));
    }

    @Test
    void storageFailuresPreserveReadWriteExceptionTypesAndCauses() throws Exception {
        var dao = newDao();
        Files.delete(tempDir.resolve("food.sqlite"));
        Files.createDirectory(tempDir.resolve("food.sqlite"));
        assertInstanceOf(DataAccessException.class, assertThrows(SQLReadException.class,
                () -> dao.loadRecordsByDate(DATE)).getCause());
        assertInstanceOf(DataAccessException.class, assertThrows(SQLPersistentException.class,
                () -> dao.insert(food("apple", DATE))).getCause());
        assertInstanceOf(DataAccessException.class, assertThrows(SQLPersistentException.class,
                () -> dao.deleteById(1)).getCause());
        assertInstanceOf(DataAccessException.class, assertThrows(SQLPersistentException.class,
                () -> dao.deleteByDate(DATE)).getCause());
        assertInstanceOf(DataAccessException.class, assertThrows(SQLPersistentException.class,
                this::newDao).getCause());
    }
}
