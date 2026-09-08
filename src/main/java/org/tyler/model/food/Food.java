package org.tyler.model.food;

/**
 * 一次食物摄入的解析结果。
 *
 * <p>由 RecordFoodTool 从 LLM 返回的 JSON 参数反序列化得到。
 * 由通用摄入信息（{@link GenericInfo}）与宏量营养素（{@link MacroNutrients}）组成。
 * 数值字段统一用 {@link java.math.BigDecimal} 承载，避免浮点精度丢失。
 *
 * @param genericInfo    通用摄入信息
 * @param macroNutrients 宏量营养素
 */
public record Food(
        GenericInfo genericInfo,
        MacroNutrients macroNutrients) {
}
