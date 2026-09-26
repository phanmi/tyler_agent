package org.tyler.model.food;

import java.math.BigDecimal;

/**
 * Macronutrients: protein, carbohydrates, fat, and dietary fiber.
 *
 * @param protein protein in grams
 * @param carbs   carbohydrates in grams
 * @param fat     fat in grams
 * @param fiber   dietary fiber in grams
 */
public record MacroNutrients(
        BigDecimal protein,
        BigDecimal carbs,
        BigDecimal fat,
        BigDecimal fiber) {
}
