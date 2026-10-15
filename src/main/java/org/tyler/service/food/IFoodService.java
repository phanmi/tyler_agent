package org.tyler.service.food;

import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import java.util.List;

/** Food use cases with stable persisted identity and calendar response DTOs. */
public interface IFoodService {
    /** Appends one entry using its embedded date and returns its generated ID and food value. */
    FoodEntry insertFood(Food food);

    /** Returns ID-bearing calendar entries for a date, or an empty list. */
    List<FoodEntry> getFoodRecordsByDate(String date);

    /** Deletes all entries on a date and reports whether any were removed. */
    boolean deleteFoodByDate(String date);

    /** Deletes exactly one entry by ID, even if multiple entries have identical values. */
    boolean deleteFoodById(long id);
}
