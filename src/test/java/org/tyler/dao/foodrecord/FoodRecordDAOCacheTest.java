package org.tyler.dao.foodrecord;

import org.junit.jupiter.api.Test;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对 {@link FoodRecordDAOCache} 的缓存命中 / 写穿 / 失败不污染语义做单元测试。
 *
 * <p>delegate 用 mock {@link FoodRecordDAOSqlite}（具体类），只验证缓存装饰器的行为，
 * 不触碰真实 SQLite IO。
 */
class FoodRecordDAOCacheTest {

    private static Food food(String name, String date) {
        return new Food(
                new GenericInfo(name, new BigDecimal("100"), "g", new BigDecimal("200"), date),
                new MacroNutrients(
                        new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("5"), new BigDecimal("1")));
    }

    @Test
    void loadFallsBackToDelegateOnFirstCall() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        List<Food> result = cache.load();

        assertEquals(1, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        verify(delegate).load();
    }

    @Test
    void loadHitsCacheOnSecondCall() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        cache.load();
        cache.load();

        verify(delegate, times(1)).load();
    }

    @Test
    void saveWritesThroughAndUpdatesCache() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);
        List<Food> toSave = List.of(food("banana", "2026-09-13"));

        cache.save(toSave);

        verify(delegate).save(toSave);
        List<Food> result = cache.load();
        assertEquals(1, result.size());
        assertEquals("banana", result.get(0).genericInfo().foodName());
        verify(delegate, never()).load();
    }

    @Test
    void saveDoesNotPolluteCacheOnDelegateFailure() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        doThrow(new IllegalStateException("disk full")).when(delegate).save(any());
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        cache.load();

        assertThrows(IllegalStateException.class,
                () -> cache.save(List.of(food("banana", "2026-09-13"))));

        List<Food> result = cache.load();
        assertEquals(1, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        verify(delegate, times(1)).load();
    }

    @Test
    void loadGroupsByDateThenFlattens() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(
                food("apple", "2026-09-12"),
                food("banana", "2026-09-13"),
                food("orange", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        List<Food> result = cache.load();

        assertEquals(3, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("orange", result.get(1).genericInfo().foodName());
        assertEquals("banana", result.get(2).genericInfo().foodName());
        verify(delegate, times(1)).load();
    }

    @Test
    void loadByDateReadsBucketDirectly() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(
                food("apple", "2026-09-12"),
                food("banana", "2026-09-13"),
                food("orange", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        List<Food> result = cache.loadByDate("2026-09-12");

        assertEquals(2, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("orange", result.get(1).genericInfo().foodName());
        cache.loadByDate("2026-09-13");
        verify(delegate, times(1)).load();
    }

    @Test
    void loadByDateReturnsEmptyWhenBucketMissing() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        assertTrue(cache.loadByDate("2026-09-13").isEmpty());
    }

    @Test
    void saveByDateWritesThroughAndAppendsToBucket() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);
        cache.load();

        Food banana = food("banana", "2026-09-12");
        cache.saveByDate("2026-09-12", banana);

        verify(delegate).saveByDate("2026-09-12", banana);
        List<Food> result = cache.loadByDate("2026-09-12");
        assertEquals(2, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("banana", result.get(1).genericInfo().foodName());
        verify(delegate, times(1)).load();
    }

    @Test
    void saveByDateDoesNotPolluteCacheOnFailure() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        doThrow(new IllegalStateException("disk full")).when(delegate).saveByDate(any(), any());
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);
        cache.load();

        assertThrows(IllegalStateException.class,
                () -> cache.saveByDate("2026-09-12", food("banana", "2026-09-12")));

        List<Food> result = cache.loadByDate("2026-09-12");
        assertEquals(1, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        verify(delegate, times(1)).load();
    }

    @Test
    void loadByDateThrowsOnBlankDate() {
        FoodRecordDAOCache cache = new FoodRecordDAOCache(mock(FoodRecordDAOSqlite.class));

        assertThrows(IllegalArgumentException.class, () -> cache.loadByDate(null));
        assertThrows(IllegalArgumentException.class, () -> cache.loadByDate("  "));
    }

    @Test
    void loadRecordsByDateDelegatesToSqlite() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        FoodRecord record = new FoodRecord(1L, food("apple", "2026-09-12"), LocalDateTime.now());
        when(delegate.loadRecordsByDate("2026-09-12")).thenReturn(List.of(record));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        List<FoodRecord> records = cache.loadRecordsByDate("2026-09-12");

        assertEquals(1, records.size());
        assertEquals(1L, records.get(0).id());
        verify(delegate).loadRecordsByDate("2026-09-12");
    }

    @Test
    void deleteByDateWritesThroughAndRemovesBucket() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(
                food("apple", "2026-09-12"),
                food("chicken", "2026-09-13")));
        when(delegate.deleteByDate("2026-09-12")).thenReturn(true);
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);
        cache.load();

        boolean removed = cache.deleteByDate("2026-09-12");

        assertTrue(removed);
        verify(delegate).deleteByDate("2026-09-12");
        assertTrue(cache.loadByDate("2026-09-12").isEmpty());
        assertEquals(1, cache.loadByDate("2026-09-13").size());
    }

    @Test
    void deleteByIdWritesThroughAndInvalidatesCache() {
        FoodRecordDAOSqlite delegate = mock(FoodRecordDAOSqlite.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        when(delegate.deleteById(1L)).thenReturn(true);
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);
        cache.load();

        boolean removed = cache.deleteById(1L);

        assertTrue(removed);
        verify(delegate).deleteById(1L);
        cache.load();
        verify(delegate, times(2)).load();
    }
}
