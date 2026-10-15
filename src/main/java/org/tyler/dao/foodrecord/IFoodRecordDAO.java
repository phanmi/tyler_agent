package org.tyler.dao.foodrecord;

import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;

import java.util.List;

/**
 * Persistence contract for reading and saving food records,
 * including date-based reads and appends.
 *
 * <p>All read methods reflect successful appends and deletions. ID-bearing reads
 * refer to the same persisted records as value-only reads.
 */
public interface IFoodRecordDAO {

    /** Reads all records, or an empty list when none exist. */
    List<Food> load();

    /** Appends the supplied records without replacing earlier records.
     * A null list or null entries are ignored. This operation does not promise batch atomicity.
     */
    void save(List<Food> foods);

    /** Reads records for a date, or an empty list if none exist; null/blank dates throw {@link IllegalArgumentException}. */
    List<Food> loadByDate(String date);

    /** Appends a food record for a date; missing arguments throw {@link IllegalArgumentException}. */
    void saveByDate(String date, Food food);

    /** Reads records with IDs and creation times, or an empty list; null/blank dates throw {@link IllegalArgumentException}. */
    List<FoodRecord> loadRecordsByDate(String date);

    /** Deletes records for a date and reports whether any were removed; missing dates throw {@link IllegalArgumentException}. */
    boolean deleteByDate(String date);

    /** Deletes one record by ID and reports whether it was removed. */
    boolean deleteById(long id);
}
