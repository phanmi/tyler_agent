package org.tyler.validation;

import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;

/** Food insertion rules shared by all callers of the food service. */
public final class FoodValidator {
    private FoodValidator() {
    }

    public static void validate(Food food) {
        if (food == null) {
            throw new IllegalArgumentException("Food must not be null");
        }
        GenericInfo info = food.genericInfo();
        if (info == null) {
            throw new IllegalArgumentException("Missing field: genericInfo");
        }
        MacroNutrients macros = food.macroNutrients();
        if (macros == null) {
            throw new IllegalArgumentException("Missing field: macroNutrients");
        }
        DomainValidation.requireText(info.foodName(), "foodName");
        DomainValidation.requireText(info.unit(), "unit");
        DomainValidation.parseDate(info.date(), "date");
        DomainValidation.requireNonnegative(info.amount(), "amount");
        DomainValidation.requireNonnegative(info.calories(), "calories");
        DomainValidation.requireNonnegative(macros.protein(), "protein");
        DomainValidation.requireNonnegative(macros.carbs(), "carbs");
        DomainValidation.requireNonnegative(macros.fat(), "fat");
        DomainValidation.requireNonnegative(macros.fiber(), "fiber");
    }
}
