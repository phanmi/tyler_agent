package org.tyler.model.food;

import java.math.BigDecimal;

/**
 * 一次食物摄入的通用信息：名称、摄入量、单位、热量、摄入日期。
 *
 * @param foodName 食物名称
 * @param amount   摄入量
 * @param unit     单位（g / ml / piece / serving …）
 * @param calories 热量（kcal）
 * @param date     摄入日期，YYYY-MM-DD
 */
public record GenericInfo(
        String foodName,
        BigDecimal amount,
        String unit,
        BigDecimal calories,
        String date) {
}
