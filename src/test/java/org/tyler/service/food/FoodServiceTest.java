package org.tyler.service.food;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionOperations;
import org.tyler.validation.FoodValidator;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.model.food.FoodRecord;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies identity-preserving DTO mapping, validation, and exception propagation. */
class FoodServiceTest {
    private final IFoodRecordDAO dao = mock(IFoodRecordDAO.class);
    private final FoodService service = new FoodService(dao, new FoodValidator(), TransactionOperations.withoutTransaction());
    private static Food food(String date) {
        return new Food(new GenericInfo("Apple", BigDecimal.ONE, "piece", BigDecimal.TEN, date),
                new MacroNutrients(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    }
    @Test
    void insertionReturnsEachGeneratedIdentityWithItsFoodInTheListsOrder() {
        Food first = food("2026-09-12");
        Food second = food("2026-09-13");
        when(dao.insert(first)).thenReturn(41L);
        when(dao.insert(second)).thenReturn(42L);
        assertEquals(List.of(new FoodEntry(41, first), new FoodEntry(42, second)),
                service.insertFoods(List.of(first, second)));
        var order = inOrder(dao);
        order.verify(dao).insert(first);
        order.verify(dao).insert(second);
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
        assertThrows(IllegalArgumentException.class, () -> service.insertFoods(Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class, () -> service.insertFoods(List.of(new Food(null, null))));
        for (String date : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> service.insertFoods(List.of(food(date))));
            // A valid food before the invalid one is not written either.
            assertThrows(IllegalArgumentException.class,
                    () -> service.insertFoods(List.of(food("2026-09-12"), food(date))));
            assertThrows(IllegalArgumentException.class, () -> service.getFoodRecordsByDate(date));
            assertThrows(IllegalArgumentException.class, () -> service.deleteFoodByDate(date));
        }
        verifyNoInteractions(dao);
    }
    @Test
    void aTransactionThatCannotOpenIsAWriteFailureAndWritesNothing() throws SQLException {
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new SQLException("Database workspace path is unavailable or unsafe"));
        FoodService real = new FoodService(dao, new FoodValidator(),
                new TransactionTemplate(new DataSourceTransactionManager(unavailable)));

        var failure = assertThrowsExactly(SQLPersistentException.class,
                () -> real.insertFoods(List.of(food("2026-09-12"))));

        assertEquals("Failed to save food records", failure.getMessage());
        assertInstanceOf(TransactionException.class, failure.getCause());
        verifyNoInteractions(dao);
    }

    @Test
    void aTransactionThatCannotCommitIsAWriteFailure() throws SQLException {
        Connection busy = mock(Connection.class);
        when(busy.getAutoCommit()).thenReturn(true);
        doThrow(new SQLException("database is locked")).when(busy).commit();
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(busy);
        Food food = food("2026-09-12");
        when(dao.insert(food)).thenReturn(41L);
        FoodService real = new FoodService(dao, new FoodValidator(),
                new TransactionTemplate(new DataSourceTransactionManager(source)));

        var failure = assertThrowsExactly(SQLPersistentException.class, () -> real.insertFoods(List.of(food)));

        assertInstanceOf(TransactionException.class, failure.getCause());
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
        assertSame(write, assertThrows(SQLPersistentException.class, () -> service.insertFoods(List.of(food))));
        assertSame(write, assertThrows(SQLPersistentException.class, () -> service.deleteFoodById(1)));
        assertSame(write, assertThrows(SQLPersistentException.class, () -> service.deleteFoodByDate("2026-09-12")));
    }
}