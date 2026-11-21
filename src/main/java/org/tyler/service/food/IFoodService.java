package org.tyler.service.food;

import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import java.util.List;

/**
 * Food use cases with stable persisted identity and calendar response DTOs.
 * Invalid domain input raises {@link IllegalArgumentException} before storage access:
 * required food fields must be present, numbers nonnegative, dates real ISO calendar dates,
 * and IDs positive. Storage exceptions propagate unchanged.
 */
public interface IFoodService {
    /**
     * Appends every food in one transaction, each under its embedded date, and returns each
     * generated ID with its food in the list's order. All of them are saved or none: every food
     * is validated before the first write, and a storage failure rolls back the ones written.
     * A transaction that cannot open or commit is a write failure like any other.
     */
    List<FoodEntry> insertFoods(List<Food> foods);

    /** Returns ID-bearing calendar entries for a date, or an empty list. */
    List<FoodEntry> getFoodRecordsByDate(String date);

    /** Deletes all entries on a date and reports whether any were removed. */
    boolean deleteFoodByDate(String date);

    /** Deletes exactly one entry by ID, even if multiple entries have identical values. */
    boolean deleteFoodById(long id);
}
