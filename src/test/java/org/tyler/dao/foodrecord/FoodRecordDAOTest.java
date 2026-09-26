package org.tyler.dao.foodrecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Tests file-based persistence in {@link FoodRecordDAO}.
 *
 * <p>Missing files, invalid JSON, and valid data use a real {@link FileSandbox}
 * under {@code @TempDir}. Permission failures use a mocked {@link IFileSandboxWrite}
 * that throws {@link FileWriteException}, avoiding platform-specific ACL behavior.
 */
class FoodRecordDAOTest {

    private static final String FILE = "food-records.json";

    @TempDir
    Path tempDir;

    private FoodRecordDAO newDao() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        return new FoodRecordDAO(sandbox, sandbox, FILE);
    }

    private static Food food(String name, String date) {
        return new Food(
                new GenericInfo(name, new BigDecimal("100"), "g", new BigDecimal("200"), date),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));
    }

    @Test
    void loadReturnsEmptyWhenFileMissing() throws IOException {
        FoodRecordDAO dao = newDao();

        assertTrue(dao.load().isEmpty());
    }

    @Test
    void loadReturnsEmptyOnCorruptedJson() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        FoodRecordDAO dao = new FoodRecordDAO(sandbox, sandbox, FILE);
        sandbox.write(FILE, "not valid json {{{");

        assertTrue(dao.load().isEmpty());
    }

    @Test
    void loadReturnsCorrectData() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        FoodRecordDAO dao = new FoodRecordDAO(sandbox, sandbox, FILE);
        // Verify the save/load serialization round trip.
        dao.save(List.of(food("apple", "2026-09-12"), food("banana", "2026-09-12")));

        List<Food> loaded = dao.load();

        assertEquals(2, loaded.size());
        assertEquals("apple", loaded.get(0).genericInfo().foodName());
        assertEquals("banana", loaded.get(1).genericInfo().foodName());
        assertEquals("2026-09-12", loaded.get(0).genericInfo().date());
    }

    @Test
    void loadByDateFiltersByDate() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        FoodRecordDAO dao = new FoodRecordDAO(sandbox, sandbox, FILE);
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
    void loadByDateReturnsEmptyWhenNoMatch() throws IOException {
        FoodRecordDAO dao = newDao();
        dao.save(List.of(food("apple", "2026-09-12")));

        assertTrue(dao.loadByDate("2026-09-13").isEmpty());
    }

    @Test
    void saveByDateAppends() throws IOException {
        FoodRecordDAO dao = newDao();
        dao.saveByDate("2026-09-12", food("apple", "2026-09-12"));
        dao.saveByDate("2026-09-12", food("banana", "2026-09-12"));

        assertEquals(2, dao.load().size());
        assertEquals(2, dao.loadByDate("2026-09-12").size());
    }

    @Test
    void loadByDateThrowsOnBlankDate() throws IOException {
        FoodRecordDAO dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.loadByDate(null));
        assertThrows(IllegalArgumentException.class, () -> dao.loadByDate("  "));
    }

    @Test
    void saveByDateThrowsOnInvalidArgs() throws IOException {
        FoodRecordDAO dao = newDao();

        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate(null, food("apple", "2026-09-12")));
        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate("  ", food("apple", "2026-09-12")));
        assertThrows(IllegalArgumentException.class, () -> dao.saveByDate("2026-09-12", null));
    }

    @Test
    void saveThrowsIllegalStateWhenPermissionDenied() {
        IFileSandboxRead reader = mock(IFileSandboxRead.class);
        IFileSandboxWrite writer = mock(IFileSandboxWrite.class);
        doThrow(new FileWriteException("permission denied")).when(writer).write(anyString(), anyString());

        FoodRecordDAO dao = new FoodRecordDAO(reader, writer, FILE);

        assertThrows(IllegalStateException.class,
                () -> dao.save(List.of(food("apple", "2026-09-12"))));
    }
}