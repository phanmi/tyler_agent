package org.tyler.dao.foodrecord;

import org.springframework.stereotype.Component;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory cache decorator implementing {@link IFoodRecordDAO}.
 *
 * <p>Groups cached records by date in a {@code Map<String, List<Food>>}.
 * Reads flatten the cached buckets when available; otherwise, the delegate
 * loads the records and populates the cache.
 * Writes update persistence first, then replace the cache only after success.
 *
 * <p>Dates come from {@code food.genericInfo().date()}; missing dates use a null bucket.
 * {@link LinkedHashMap} preserves order and supports null keys.
 *
 * <p>Synchronized reads and writes protect this singleton bean;
 * defensive copies keep callers from modifying the cache.
 */
@Component
public class FoodRecordDAOCache implements IFoodRecordDAO {

    private final FoodRecordDAOSqlite delegate;

    /** Cache grouped by date; volatile provides visibility across threads. */
    private volatile Map<String, List<Food>> cached;

    public FoodRecordDAOCache(FoodRecordDAOSqlite delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized List<Food> load() {
        if (cached != null) {
            return flatten(cached);
        }
        List<Food> loaded = delegate.load();
        cached = bucketByDate(loaded);
        return flatten(cached);
    }

    @Override
    public synchronized void save(List<Food> foods) {
        // Persist first, then replace the cache; keep the previous cache if writing fails.
        delegate.save(foods);
        cached = bucketByDate(foods);
    }

    private static String dateOf(Food food) {
        return food.genericInfo() == null ? null : food.genericInfo().date();
    }

    /** Groups records by date; LinkedHashMap preserves order and allows null keys. */
    private static Map<String, List<Food>> bucketByDate(List<Food> foods) {
        Map<String, List<Food>> buckets = new LinkedHashMap<>();
        if (foods != null) {
            for (Food food : foods) {
                buckets.computeIfAbsent(dateOf(food), k -> new ArrayList<>()).add(food);
            }
        }
        return buckets;
    }

    /** Flattens the buckets into a fresh list as a defensive copy. */
    private static List<Food> flatten(Map<String, List<Food>> buckets) {
        List<Food> result = new ArrayList<>();
        for (List<Food> bucket : buckets.values()) {
            result.addAll(bucket);
        }
        return result;
    }

    @Override
    public synchronized List<Food> loadByDate(String date) {
        requireDate(date);
        // Populate a cold cache once; otherwise, retrieve the date bucket directly.
        if (cached == null) {
            cached = bucketByDate(delegate.load());
        }
        List<Food> bucket = cached.get(date);
        return bucket == null ? List.of() : new ArrayList<>(bucket);
    }

    @Override
    public synchronized void saveByDate(String date, Food food) {
        // Persist first, then append to the date bucket; failed writes leave the cache intact.
        delegate.saveByDate(date, food);
        if (cached != null) {
            cached.computeIfAbsent(date, k -> new ArrayList<>()).add(food);
        }
    }

    @Override
    public synchronized List<FoodRecord> loadRecordsByDate(String date) {
        // Read records with IDs from the delegate because cached Food values have no IDs.
        requireDate(date);
        return delegate.loadRecordsByDate(date);
    }

    @Override
    public synchronized boolean deleteByDate(String date) {
        requireDate(date);
        boolean removed = delegate.deleteByDate(date);
        if (removed && cached != null) {
            cached.remove(date);
        }
        return removed;
    }

    @Override
    public synchronized boolean deleteById(long id) {
        boolean removed = delegate.deleteById(id);
        if (removed) {
            // IDs cannot be located in the cache, so invalidate it for the next read.
            cached = null;
        }
        return removed;
    }

    private static void requireDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("Date must not be blank");
        }
    }
}
