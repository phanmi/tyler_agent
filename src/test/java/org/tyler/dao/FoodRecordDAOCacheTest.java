package org.tyler.dao;

import org.junit.jupiter.api.Test;
import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

import java.math.BigDecimal;
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
 * <p>delegate 用 mock {@link FoodRecordDAO}（具体类），只验证缓存装饰器的行为，
 * 不触碰真实文件 IO。
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
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        List<Food> result = cache.load();

        assertEquals(1, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        verify(delegate).load();
    }

    @Test
    void loadHitsCacheOnSecondCall() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        cache.load();
        cache.load();

        verify(delegate, times(1)).load();
    }

    @Test
    void saveWritesThroughAndUpdatesCache() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);
        List<Food> toSave = List.of(food("banana", "2026-09-13"));

        cache.save(toSave);

        verify(delegate).save(toSave);
        List<Food> result = cache.load();
        assertEquals(1, result.size());
        assertEquals("banana", result.get(0).genericInfo().foodName());
        // 缓存已更新：load 命中缓存，不再回源。
        verify(delegate, never()).load();
    }

    @Test
    void saveDoesNotPolluteCacheOnDelegateFailure() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        doThrow(new IllegalStateException("disk full")).when(delegate).save(any());
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        cache.load(); // 缓存 = [apple]

        assertThrows(IllegalStateException.class,
                () -> cache.save(List.of(food("banana", "2026-09-13"))));

        // 缓存未被污染：仍返回 [apple]
        List<Food> result = cache.load();
        assertEquals(1, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        verify(delegate, times(1)).load();
    }

    @Test
    void loadGroupsByDateThenFlattens() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(
                food("apple", "2026-09-12"),
                food("banana", "2026-09-13"),
                food("orange", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        List<Food> result = cache.load();

        // 分桶后展平：先 09-12 桶（apple, orange），再 09-13 桶（banana），证明按日期分桶而非平铺。
        assertEquals(3, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("orange", result.get(1).genericInfo().foodName());
        assertEquals("banana", result.get(2).genericInfo().foodName());
        verify(delegate, times(1)).load();
    }

    @Test
    void saveRebucketsAndReplacesCache() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        cache.load(); // 缓存 = {09-12: [apple]}

        cache.save(List.of(
                food("banana", "2026-09-13"),
                food("orange", "2026-09-13")));

        // save 为覆盖写：旧的 apple 被替换，只剩 09-13 桶的两条。
        List<Food> result = cache.load();
        assertEquals(2, result.size());
        assertEquals("banana", result.get(0).genericInfo().foodName());
        assertEquals("orange", result.get(1).genericInfo().foodName());
        verify(delegate, times(1)).load();
        verify(delegate, times(1)).save(any());
    }

    @Test
    void loadByDateReadsBucketDirectly() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(
                food("apple", "2026-09-12"),
                food("banana", "2026-09-13"),
                food("orange", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        List<Food> result = cache.loadByDate("2026-09-12");

        assertEquals(2, result.size());
        assertEquals("apple", result.get(0).genericInfo().foodName());
        assertEquals("orange", result.get(1).genericInfo().foodName());
        // 再按另一日期取，仍命中同一份缓存，不回源。
        cache.loadByDate("2026-09-13");
        verify(delegate, times(1)).load();
    }

    @Test
    void loadByDateReturnsEmptyWhenBucketMissing() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);

        assertTrue(cache.loadByDate("2026-09-13").isEmpty());
    }

    @Test
    void saveByDateWritesThroughAndAppendsToBucket() {
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
        when(delegate.load()).thenReturn(List.of(food("apple", "2026-09-12")));
        FoodRecordDAOCache cache = new FoodRecordDAOCache(delegate);
        cache.load(); // 缓存 = {09-12: [apple]}

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
        FoodRecordDAO delegate = mock(FoodRecordDAO.class);
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
        FoodRecordDAOCache cache = new FoodRecordDAOCache(mock(FoodRecordDAO.class));

        assertThrows(IllegalArgumentException.class, () -> cache.loadByDate(null));
        assertThrows(IllegalArgumentException.class, () -> cache.loadByDate("  "));
    }
}
