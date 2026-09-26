package org.tyler.controller.food;

import org.tyler.model.food.FoodEntry;

import java.util.List;

/**
 * REST contract for food records.
 *
 * <p>Declares HTTP operations; data records such as {@link FoodEntry} live in
 * the {@link org.tyler.model.food} package.
 */
public interface IFoodController {

    /** Returns records with IDs for a YYYY-MM-DD date, or an empty list if none exist. */
    List<FoodEntry> foodByDate(String date);

    /** Deletes one record by ID and reports whether it was removed. */
    boolean deleteFood(long id);

    /** Deletes all records for a YYYY-MM-DD date and reports whether any were removed. */
    boolean deleteFoodByDate(String date);
}