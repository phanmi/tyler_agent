package org.tyler.tool.workoutPlanTool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.model.workout.WorkoutPlan;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.tool.ITool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts model arguments and JSON results to the shared workout plan service.
 */
@Component
public class GenerateWorkoutPlanTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IWorkoutPlanService planService;

    public GenerateWorkoutPlanTool(IWorkoutPlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return "generateWorkoutPlan";
    }

    @Override
    public String description() {
        return "Generate and save a seven-day workout plan when the user asks to schedule workouts. "
                + "Use general_fitness as the goal and bodyweight as the equipment when the user has no preference. "
                + "Use getCurrentTime to determine today's date when needed. "
                + "The plan includes strength, cardio, and recovery days. "
                + "Save its exercises to the workout database without overwriting existing workouts. "
                + "Weight 0 is a placeholder until the user chooses a training load.";
    }

    @Override
    public String execute(String argumentsJson) {
        JsonNode arguments;
        try {
            arguments = MAPPER.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse workout plan arguments: " + e.getMessage(), e);
        }
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("Workout plan arguments must be a JSON object");
        }

        WorkoutPlan savedPlan = planService.generateAndSave(requiredText(arguments, "startDate"),
                requiredText(arguments, "goal"), requiredText(arguments, "equipment"));

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(savedPlan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workout plan", e);
        }
    }

    private static String requiredText(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid field: " + field);
        }
        return value.asText();
    }

    @Override
    public FunctionTool toFunctionTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("startDate", Map.of(
                "type", "string", "description", "First plan date in YYYY-MM-DD format."));
        properties.put("goal", Map.of(
                "type", "string", "enum", List.of("general_fitness", "strength", "muscle_gain"),
                "description", "Training goal; use general_fitness when unspecified."));
        properties.put("equipment", Map.of(
                "type", "string", "enum", List.of("bodyweight", "gym"),
                "description", "Available equipment; use bodyweight when unspecified."));
        return FunctionTool.builder()
                .name(name())
                .description(description())
                .parameters(FunctionTool.Parameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(properties))
                        .putAdditionalProperty("required", JsonValue.from(List.of("startDate", "goal", "equipment")))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .strict(true)
                .build();
    }

}
