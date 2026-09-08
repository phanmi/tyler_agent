package org.tyler.model.food;

import java.math.BigDecimal;

/**
 * 宏量营养素：蛋白质 / 碳水化合物 / 脂肪 / 膳食纤维。
 *
 * @param protein 蛋白质（g）
 * @param carbs   碳水化合物（g）
 * @param fat     脂肪（g）
 * @param fiber   膳食纤维（g）
 */
public record MacroNutrients(
        BigDecimal protein,
        BigDecimal carbs,
        BigDecimal fat,
        BigDecimal fiber) {
}
