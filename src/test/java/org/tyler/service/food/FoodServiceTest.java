package org.tyler.service.food;

import org.junit.jupiter.api.Test;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.model.food.FoodRecord;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies identity-preserving DTO mapping, validation, and exception propagation. */
class FoodServiceTest {
    private final IFoodRecordDAO dao = mock(IFoodRecordDAO.class);
    private final FoodService service = new FoodService(dao);
    private static Food food(String date) {
        return new Food(new GenericInfo("Apple", BigDecimal.ONE, "piece", BigDecimal.TEN, date),
                new MacroNutrients(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    }
    @Test
    void insertionReturnsGeneratedIdentityAndOriginalFood() {
        Food food = food("2026-09-12");
        when(dao.insert(food)).thenReturn(41L);
        assertEquals(new FoodEntry(41, food), service.insertFood(food));
        verify(dao).insert(food);
        verifyNoMoreInteractions(dao);
    }
    @Test
    void lookupPreservesSeparateIdsForIdenticalValuesAndDeleteUsesOnlyId() {
        Food food = food("2026-09-12");
        when(dao.loadRecordsByDate("2026-09-12")).thenReturn(List.of(
                new FoodRecord(1, food, LocalDateTime.now()), new FoodRecord(2, food, LocalDateTime.now())));
        assertEquals(List.of(new FoodEntry(1, food), new FoodEntry(2, food)), service.getFoodRecordsByDate("2026-09-12"));
        when(dao.deleteById(2)).thenReturn(true);
        assertTrue(service.deleteFoodById(2));
        verify(dao).loadRecordsByDate("2026-09-12");
        verify(dao).deleteById(2);
        verifyNoMoreInteractions(dao);
    }
    @Test
    void absentRecordsAndDateDeletionKeepTheirContracts() {
        when(dao.loadRecordsByDate("2026-09-12")).thenReturn(List.of());
        assertTrue(service.getFoodRecordsByDate("2026-09-12").isEmpty());
        assertFalse(service.deleteFoodById(999));
        assertFalse(service.deleteFoodByDate("2026-09-12"));
        when(dao.deleteByDate("2026-09-12")).thenReturn(true);
        assertTrue(service.deleteFoodByDate("2026-09-12"));
    }
    @Test
    void invalidInputsNeverAccessStorage() {
        assertThrows(IllegalArgumentException.class, () -> service.insertFood(null));
        assertThrows(IllegalArgumentException.class, () -> service.insertFood(new Food(null, null)));
        for (String date : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> service.insertFood(food(date)));
            assertThrows(IllegalArgumentException.class, () -> service.getFoodRecordsByDate(date));
            assertThrows(IllegalArgumentException.class, () -> service.deleteFoodByDate(date));
        }
        verifyNoInteractions(dao);
    }
    @Test
    void persistenceExceptionsPropagateWithoutChangingTheirType() {
        Food food = food("2026-09-12");
        SQLReadException read = new SQLReadException("Read failed");
        SQLPersistentException write = new SQLPersistentException("Write failed");
        when(dao.loadRecordsByDate("2026-09-12")).thenThrow(read);
        when(dao.insert(food)).thenThrow(write);
        when(dao.deleteById(1)).thenThrow(write);
        when(dao.deleteByDate("2026-09-12")).thenThrow(write);
        assertSame(read, assertThrows(SQLReadException.class, () -> service.getFoodRecordsByDate("2026-09-12")));
        assertSame(write, assertThrows(SQLPersistentException.class, () -> service.insertFood(food)));
        assertSame(write, assertThrows(SQLPersistentException.class, () -> service.deleteFoodById(1)));
        assertSame(write, assertThrows(SQLPersistentException.class, () -> service.deleteFoodByDate("2026-09-12")));
    }
}