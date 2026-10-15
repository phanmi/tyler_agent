package org.tyler.dao.foodrecord;

import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;
import java.util.List;

/** Persists independently addressable food entries; equal values do not imply equal identity. */
public interface IFoodRecordDAO {
    /** Appends one record using Food.genericInfo.date as its sole date source and returns its ID.
     * Identical entries are inserted separately. Null food or null/blank dates are invalid arguments.
     */
    long insert(Food food);

    /** Reads records with IDs and creation times in ascending ID order; returns empty if absent.
     * Null/blank dates throw IllegalArgumentException.
     */
    List<FoodRecord> loadRecordsByDate(String date);

    /** Deletes all records on the date; null/blank dates throw IllegalArgumentException. */
    boolean deleteByDate(String date);

    /** Deletes only the identified record, returning false if it does not exist. */
    boolean deleteById(long id);
}
