package org.tyler.tool.foodRecordTool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.model.food.Food;
import org.tyler.model.food.GenericInfo;
import org.tyler.model.food.MacroNutrients;
import org.tyler.tool.ITool;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让 LLM 解析用户每天吃的食物，输出结构化的 {@link Food}。
 *
 * <p>当前阶段只负责「解析」、不落盘：把模型生成的 JSON 参数反序列化成
 * {@link Food} 并校验字段，再把结构化结果回显给模型。
 */
@Component
public class RecordFoodTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "recordFood";
    }

    @Override
    public String description() {
        return "Parse a single food item the user consumed into a structured record. "
                + "Use this when the user tells you what they ate. "
                + "Estimate the nutrition values (calories, protein, carbs, fat, fiber) "
                + "from the food name and amount when they are not explicitly given.";
    }

    @Override
    public String execute(String argumentsJson) {
        Food food;
        try {
            food = MAPPER.readValue(argumentsJson, Food.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("解析食物参数失败：" + e.getMessage(), e);
        }
        validate(food);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(food);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化食物记录失败", e);
        }
    }

    private static void validate(Food food) {
        GenericInfo info = food.genericInfo();
        if (info == null) {
            throw new IllegalArgumentException("缺少字段 genericInfo");
        }
        MacroNutrients macros = food.macroNutrients();
        if (macros == null) {
            throw new IllegalArgumentException("缺少字段 macroNutrients");
        }
        requireText(info.foodName(), "foodName");
        requireText(info.unit(), "unit");
        requireText(info.date(), "date");
        requireNumber(info.amount(), "amount");
        requireNumber(info.calories(), "calories");
        requireNumber(macros.protein(), "protein");
        requireNumber(macros.carbs(), "carbs");
        requireNumber(macros.fat(), "fat");
        requireNumber(macros.fiber(), "fiber");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少字段 " + field);
        }
    }

    private static void requireNumber(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("缺少字段 " + field);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("字段 " + field + " 不能为负数");
        }
    }

    @Override
    public FunctionTool toFunctionTool() {
        return FunctionTool.builder()
                .name(name())
                .description(description())
                .parameters(
                        FunctionTool.Parameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(properties()))
                                .putAdditionalProperty("required", JsonValue.from(List.of(
                                        "genericInfo", "macroNutrients")))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                .strict(true)
                .build();
    }

    private static Map<String, Object> properties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("genericInfo", Map.of(
                "type", "object",
                "description", "General info about the consumed food.",
                "properties", genericInfoProperties(),
                "required", List.of("foodName", "amount", "unit", "calories", "date"),
                "additionalProperties", false));
        props.put("macroNutrients", Map.of(
                "type", "object",
                "description", "Macronutrient breakdown.",
                "properties", macroNutrientProperties(),
                "required", List.of("protein", "carbs", "fat", "fiber"),
                "additionalProperties", false));
        return props;
    }

    private static Map<String, Object> genericInfoProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("foodName", Map.of("type", "string", "description", "Name of the food consumed."));
        props.put("amount", Map.of("type", "number", "description", "Amount consumed."));
        props.put("unit", Map.of("type", "string", "description", "Unit of the amount, such as g, ml, piece, serving."));
        props.put("calories", Map.of("type", "number", "description", "Calories in kcal."));
        props.put("date", Map.of("type", "string", "description", "Date the food was consumed, in YYYY-MM-DD format."));
        return props;
    }

    private static Map<String, Object> macroNutrientProperties() { 
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("protein", Map.of("type", "number", "description", "Protein in grams."));
        props.put("carbs", Map.of("type", "number", "description", "Carbohydrates in grams."));
        props.put("fat", Map.of("type", "number", "description", "Fat in grams."));
        props.put("fiber", Map.of("type", "number", "description", "Dietary fiber in grams."));
        return props;
    }
}