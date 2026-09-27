package org.tyler.service.food;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.dao.foodrecord.FoodRecordDAOSqlite;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * Tests date-based operations in {@link FoodService}.
 *
 * <p>Uses a real SQLite DAO and {@link FileSandbox} under {@code @TempDir}
 * to verify filtering, appends, deletion, and reading all records.
 */
class FoodServiceTest {

    private static final String DB_FILE = "food-record.sqlite";

    @TempDir
    Path tempDir;

    private FoodService newService() {
        try {
            FileSandbox sandbox = new FileSandbox(tempDir.toString());
            FoodRecordDAOSqlite dao = new FoodRecordDAOSqlite(sandbox, DB_FILE);
            return new FoodService(dao);
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

    @Test
    void invalidSavesLeaveExistingRecordsUnchanged() {
        FoodService service = newService();
        Food original = food("apple", "2026-09-12");
        assertSame(original, service.saveFoodByDate(original));
        assertThrows(IllegalArgumentException.class, () -> service.saveFoodByDate(null));
        assertThrows(IllegalArgumentException.class, () -> service.saveFoodByDate(food("apple", null)));
        assertThrows(IllegalArgumentException.class, () -> service.saveFoodByDate(food("apple", "  ")));
        assertThrows(IllegalArgumentException.class, () -> service.saveFoodByDate(new Food(null, original.macroNutrients())));
        assertEquals(List.of(original), service.getAllFoodRecords());
    }

    @Test
    void deleteEqualFoodRemovesOnlyTheFirstRecord() {
        FoodService service = newService();
        Food duplicate = food("apple", "2026-09-12");
        service.saveFoodByDate(duplicate);
        service.saveFoodByDate(duplicate);
        List<FoodEntry> before = service.getFoodRecordsByDate("2026-09-12");
        assertTrue(service.deleteFoodFromDate(duplicate, "2026-09-12"));
        assertEquals(List.of(before.get(1)), service.getFoodRecordsByDate("2026-09-12"));
    }

    @Test
    void storageFailuresRetainTheirExceptionTypes() {
        IFoodRecordDAO dao = mock(IFoodRecordDAO.class);
        FoodService service = new FoodService(dao);
        SQLReadException readFailure = new SQLReadException("Read failed");
        SQLPersistentException writeFailure = new SQLPersistentException("Write failed");
        Food sample = food("apple", "2026-09-12");
        when(dao.loadByDate("2026-09-12")).thenThrow(readFailure);
        doThrow(writeFailure).when(dao).saveByDate("2026-09-12", sample);
        when(dao.deleteById(1)).thenThrow(writeFailure);
        assertSame(readFailure, assertThrows(SQLReadException.class,
                () -> service.getFoodByDate("2026-09-12")));
        assertSame(writeFailure, assertThrows(SQLPersistentException.class,
                () -> service.saveFoodByDate(sample)));
        assertSame(writeFailure, assertThrows(SQLPersistentException.class,
                () -> service.deleteFoodById(1)));
    }

    @Test
    void getFoodByDateFiltersByDate() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));
        service.saveFoodByDate(food("banana", "2026-09-12"));
        service.saveFoodByDate(food("chicken", "2026-09-13"));

        List<Food> result = service.getFoodByDate("2026-09-12");

        assertEquals(2, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("banana", result.get(1).genericInfo().foodName());
    }

    @Test
    void saveFoodByDateAppends() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));
        service.saveFoodByDate(food("banana", "2026-09-12"));

        assertEquals(2, service.getAllFoodRecords().size());
        assertEquals(2, service.getFoodByDate("2026-09-12").size());
    }

    @Test
    void deleteFoodByDateRemovesOnlyThatDate() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));
        service.saveFoodByDate(food("banana", "2026-09-12"));
        service.saveFoodByDate(food("chicken", "2026-09-13"));

        boolean removed = service.deleteFoodByDate("2026-09-12");

        assertTrue(removed);
        assertEquals(1, service.getAllFoodRecords().size());
        assertEquals("chicken", service.getAllFoodRecords().get(0).genericInfo().foodName());
        assertTrue(service.getFoodByDate("2026-09-12").isEmpty());
    }

    @Test
    void deleteFoodByDateReturnsFalseWhenNoMatch() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));

        boolean removed = service.deleteFoodByDate("2026-09-13");

        assertFalse(removed);
        assertEquals(1, service.getAllFoodRecords().size());
    }

    @Test
    void getAllFoodRecordsReturnsEverything() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));
        service.saveFoodByDate(food("chicken", "2026-09-13"));

        assertEquals(2, service.getAllFoodRecords().size());
    }

    @Test
    void blankDateThrows() throws IOException {
        FoodService service = newService();

        assertThrows(IllegalArgumentException.class, () -> service.getFoodByDate("  "));
        assertThrows(IllegalArgumentException.class, () -> service.getFoodByDate(null));
        assertThrows(IllegalArgumentException.class, () -> service.deleteFoodByDate("  "));
    }

    @Test
    void deleteFoodFromDateRemovesOnlyMatchingRecord() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));
        service.saveFoodByDate(food("banana", "2026-09-12"));
        service.saveFoodByDate(food("chicken", "2026-09-13"));

        boolean removed = service.deleteFoodFromDate(food("banana", "2026-09-12"), "2026-09-12");

        assertTrue(removed);
        assertEquals(2, service.getAllFoodRecords().size());
        List<Food> remaining = service.getFoodByDate("2026-09-12");
        assertEquals(1, remaining.size());
        assertEquals("apple", remaining.get(0).genericInfo().foodName());
    }

    @Test
    void deleteFoodFromDateReturnsFalseWhenNoMatch() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));

        boolean removed = service.deleteFoodFromDate(food("banana", "2026-09-12"), "2026-09-12");

        assertFalse(removed);
        assertEquals(1, service.getAllFoodRecords().size());
    }

    @Test
    void deleteFoodFromDateThrowsOnInvalidArgs() throws IOException {
        FoodService service = newService();

        assertThrows(IllegalArgumentException.class, () -> service.deleteFoodFromDate(food("apple", "2026-09-12"), null));
        assertThrows(IllegalArgumentException.class, () -> service.deleteFoodFromDate(food("apple", "2026-09-12"), "  "));
        assertThrows(IllegalArgumentException.class, () -> service.deleteFoodFromDate(null, "2026-09-12"));
    }

    @Test
    void getFoodRecordsByDateReturnsEntriesWithIds() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));
        service.saveFoodByDate(food("banana", "2026-09-12"));
        service.saveFoodByDate(food("chicken", "2026-09-13"));

        List<FoodEntry> result = service.getFoodRecordsByDate("2026-09-12");

        assertEquals(2, result.size());
        assertEquals("apple", result.get(0).food().genericInfo().foodName());
        assertEquals("banana", result.get(1).food().genericInfo().foodName());
        // SQLite IDs should be positive, unique, and increasing.
        assertTrue(result.get(0).id() > 0);
        assertTrue(result.get(1).id() > result.get(0).id());
    }

    @Test
    void getFoodRecordsByDateReturnsEmptyWhenNoMatch() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));

        assertTrue(service.getFoodRecordsByDate("2026-09-13").isEmpty());
    }

    @Test
    void deleteFoodByIdRemovesSingleRecord() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));
        service.saveFoodByDate(food("banana", "2026-09-12"));

        long id = service.getFoodRecordsByDate("2026-09-12").get(0).id();

        boolean removed = service.deleteFoodById(id);

        assertTrue(removed);
        List<FoodEntry> remaining = service.getFoodRecordsByDate("2026-09-12");
        assertEquals(1, remaining.size());
        assertEquals("banana", remaining.get(0).food().genericInfo().foodName());
    }

    @Test
    void deleteFoodByIdReturnsFalseWhenNoMatch() throws IOException {
        FoodService service = newService();
        service.saveFoodByDate(food("apple", "2026-09-12"));

        assertFalse(service.deleteFoodById(999999L));
        assertEquals(1, service.getAllFoodRecords().size());
    }
}
