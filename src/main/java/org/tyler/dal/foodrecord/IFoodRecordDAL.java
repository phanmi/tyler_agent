package org.tyler.dal.foodrecord;

import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;

import java.util.List;

/**
 * Contract for date-based food record operations.
 *
 * <p>The DAO handles JSON or database persistence; this layer exposes
 * reading, saving, and deleting records by date, plus reading all records.
 */
public interface IFoodRecordDAL {

    /** Returns food records for a YYYY-MM-DD date, or an empty list. */
    List<Food> getFoodByDate(String date);

    /** Appends a food record using its date and returns the saved record. */
    Food saveFoodByDate(Food food);

    /** Deletes all records for a date and reports whether any were removed. */
    boolean deleteFoodByDate(String date);

    /** Returns all food records. */
    List<Food> getAllFoodRecords();

    /** Deletes the first structurally equal food record on the given date and reports success. */
    boolean deleteFoodFromDate(Food food, String date);

    /** Returns records with IDs for a YYYY-MM-DD date, or an empty list. */
    List<FoodEntry> getFoodRecordsByDate(String date);

    /** Deletes one record by ID and reports whether it was removed. */
    boolean deleteFoodById(long id);
}