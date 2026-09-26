package org.tyler.model.food;

import java.math.BigDecimal;

/**
 * General food intake information: name, amount, unit, calories, and date.
 *
 * @param foodName food name
 * @param amount   amount consumed
 * @param unit     unit, such as g, ml, piece, or serving
 * @param calories energy in kcal
 * @param date     intake date in YYYY-MM-DD format
 */
public record GenericInfo(
        String foodName,
        BigDecimal amount,
        String unit,
        BigDecimal calories,
        String date) {
}
