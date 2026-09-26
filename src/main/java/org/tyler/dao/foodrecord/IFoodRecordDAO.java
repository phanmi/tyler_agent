package org.tyler.dao.foodrecord;

import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;

import java.util.List;

/**
 * Persistence contract for reading and saving food records,
 * including date-based reads and appends.
 *
 * <p>{@code load} and {@code save} handle lists; {@code loadByDate} and
 * {@code saveByDate} allow direct access to the date buckets in
 * {@link FoodRecordDAOCache}.
 */
public interface IFoodRecordDAO {

    /** Reads all records; the JSON implementation returns an empty list for missing or invalid files. */
    List<Food> load();

    /** Saves the list; JSON replaces its file, while SQLite appends the supplied records. */
    void save(List<Food> foods);

    /** Reads records for a YYYY-MM-DD date; missing dates throw {@link IllegalArgumentException}. */
    List<Food> loadByDate(String date);

    /** Appends a food record for a date; missing arguments throw {@link IllegalArgumentException}. */
    void saveByDate(String date, Food food);

    /** Reads records with IDs and creation times for a date; missing dates throw {@link IllegalArgumentException}. */
    List<FoodRecord> loadRecordsByDate(String date);

    /** Deletes records for a date and reports whether any were removed; missing dates throw {@link IllegalArgumentException}. */
    boolean deleteByDate(String date);

    /** Deletes one record by ID and reports whether it was removed. */
    boolean deleteById(long id);
}