package org.tyler.model.food;

/**
 * Parsed information for one food intake entry.
 *
 * <p>RecordFoodTool deserializes the model's JSON arguments into this record.
 * Combines {@link GenericInfo} with {@link MacroNutrients}.
 * Numeric fields use {@link java.math.BigDecimal} to preserve decimal precision.
 *
 * @param genericInfo    general intake information
 * @param macroNutrients macronutrient values
 */
public record Food(
        GenericInfo genericInfo,
        MacroNutrients macroNutrients) {
}
