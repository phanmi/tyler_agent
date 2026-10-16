package org.tyler.tool.foodRecordTool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.service.food.IFoodService;
import org.tyler.model.food.Food;
import org.tyler.tool.ITool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a user's food description into a structured {@link Food} record.
 *
 * <p>Deserializes the model's JSON arguments, delegates domain validation and saving
 * to {@link IFoodService}, and serializes the saved food value for the model.
 */
@Component
public class RecordFoodTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IFoodService foodService;

    public RecordFoodTool(IFoodService foodService) {
        this.foodService = foodService;
    }

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
            throw new IllegalArgumentException("Failed to parse food arguments: " + e.getMessage(), e);
        }
        // Preserve the food-only tool response while the service exposes persisted identity.
        Food saved = foodService.insertFood(food).food();
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(saved);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize food record", e);
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
