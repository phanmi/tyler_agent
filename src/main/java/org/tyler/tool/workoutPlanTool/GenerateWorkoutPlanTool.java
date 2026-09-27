package org.tyler.tool.workoutPlanTool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.model.workout.Workout;
import org.tyler.service.workout.IWorkoutService;
import org.tyler.tool.ITool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a seven-day workout plan and saves its exercises to the workout database.
 */
@Component
public class GenerateWorkoutPlanTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IWorkoutService workoutService;

    public GenerateWorkoutPlanTool(IWorkoutService workoutService) {
        this.workoutService = workoutService;
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

        WorkoutPlan plan = previewPlan(requiredText(arguments, "startDate"),
                requiredText(arguments, "goal"), requiredText(arguments, "equipment"));
        WorkoutPlan savedPlan = savePlan(plan);

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(savedPlan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workout plan", e);
        }
    }

    /** Builds a read-only preview for the calendar. The agent tool's execute method saves it. */
    public WorkoutPlan previewPlan(String startDate, String goal, String equipment) {
        return generate(parseStartDate(startDate), parseGoal(goal), parseEquipment(equipment));
    }

    private WorkoutPlan savePlan(WorkoutPlan plan) {
        Map<WorkoutKey, Workout> saved = new HashMap<>();
        for (Workout workout : workoutService.getAllWorkouts().values()) {
            saved.put(new WorkoutKey(workout.workoutDate(), workout.workoutName()), workout);
        }

        List<Long> insertedIds = new ArrayList<>();
        List<WorkoutPlanDay> days = new ArrayList<>(plan.days().size());
        try {
            for (WorkoutPlanDay day : plan.days()) {
                List<Workout> workouts = new ArrayList<>(day.workouts().size());
                for (Workout workout : day.workouts()) {
                    WorkoutKey key = new WorkoutKey(workout.workoutDate(), workout.workoutName());
                    Workout existing = saved.get(key);
                    if (existing == null) {
                        insertedIds.add(workoutService.saveWorkout(workout));
                        saved.put(key, workout);
                        workouts.add(workout);
                    } else {
                        workouts.add(existing);
                    }
                }
                days.add(new WorkoutPlanDay(day.date(), day.focus(), List.copyOf(workouts),
                        day.activity(), day.durationMinutes()));
            }
        } catch (RuntimeException failure) {
            for (int index = insertedIds.size() - 1; index >= 0; index--) {
                try {
                    workoutService.deleteWorkout(insertedIds.get(index));
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        return new WorkoutPlan(plan.startDate(), plan.goal(), plan.equipment(),
                plan.weightGuidance(), List.copyOf(days));
    }

    private record WorkoutKey(String date, String name) {
    }

    private static String requiredText(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid field: " + field);
        }
        return value.asText();
    }

    private static LocalDate parseStartDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("startDate must use the format YYYY-MM-DD", e);
        }
    }

    private static Goal parseGoal(String value) {
        return switch (value) {
            case "strength" -> Goal.STRENGTH;
            case "muscle_gain" -> Goal.MUSCLE_GAIN;
            case "general_fitness" -> Goal.GENERAL_FITNESS;
            default -> throw new IllegalArgumentException("Unsupported goal: " + value);
        };
    }

    private static Equipment parseEquipment(String value) {
        return switch (value) {
            case "bodyweight" -> Equipment.BODYWEIGHT;
            case "gym" -> Equipment.GYM;
            default -> throw new IllegalArgumentException("Unsupported equipment: " + value);
        };
    }

    private static WorkoutPlan generate(LocalDate startDate, Goal goal, Equipment equipment) {
        List<WorkoutPlanDay> days = new ArrayList<>(7);
        days.add(strengthDay(startDate, 0, "Full body A", goal, equipment));
        days.add(activityDay(startDate, 1, "Cardio", "Brisk walk or cycle", 25));
        days.add(strengthDay(startDate, 2, "Full body B", goal, equipment));
        days.add(activityDay(startDate, 3, "Recovery", "Gentle mobility or an easy walk", 20));
        days.add(strengthDay(startDate, 4, "Full body C", goal, equipment));
        days.add(activityDay(startDate, 5, "Cardio", "Brisk walk or cycle", 30));
        days.add(activityDay(startDate, 6, "Rest", "Rest", 0));
        return new WorkoutPlan(startDate.toString(), goal.value, equipment.value,
                "Weight 0 is a placeholder; choose a suitable load for each exercise.", List.copyOf(days));
    }

    private static WorkoutPlanDay strengthDay(LocalDate startDate, int offset, String focus,
                                              Goal goal, Equipment equipment) {
        String date = startDate.plusDays(offset).toString();
        String[] names = exerciseNames(offset, equipment);
        List<Workout> workouts = new ArrayList<>(names.length);
        for (String exerciseName : names) {
            workouts.add(new Workout(exerciseName, goal.rep, BigDecimal.ZERO, date));
        }
        return new WorkoutPlanDay(date, focus, List.copyOf(workouts), null, 0);
    }

    private static WorkoutPlanDay activityDay(LocalDate startDate, int offset, String focus,
                                              String activity, int durationMinutes) {
        String date = startDate.plusDays(offset).toString();
        List<Workout> workouts = durationMinutes == 0 ? List.of()
                : List.of(new Workout(activity + " (" + durationMinutes + " minutes)",
                        "1X1", BigDecimal.ZERO, date));
        return new WorkoutPlanDay(date, focus, workouts, activity, durationMinutes);
    }

    private static String[] exerciseNames(int offset, Equipment equipment) {
        if (equipment == Equipment.BODYWEIGHT) {
            return switch (offset) {
                case 0 -> new String[]{"Bodyweight squat", "Push-up", "Glute bridge"};
                case 2 -> new String[]{"Reverse lunge", "Incline push-up", "Superman"};
                case 4 -> new String[]{"Step-up", "Pike push-up", "Bird dog"};
                default -> throw new IllegalArgumentException("Unsupported strength day: " + offset);
            };
        }
        return switch (offset) {
            case 0 -> new String[]{"Goblet squat", "Bench press", "Seated cable row"};
            case 2 -> new String[]{"Romanian deadlift", "Overhead press", "Lat pulldown"};
            case 4 -> new String[]{"Walking lunge", "Incline dumbbell press", "Seated cable row"};
            default -> throw new IllegalArgumentException("Unsupported strength day: " + offset);
        };
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

    private enum Goal {
        GENERAL_FITNESS("general_fitness", "3X12"),
        STRENGTH("strength", "4X6"),
        MUSCLE_GAIN("muscle_gain", "3X10");

        private final String value;
        private final String rep;

        Goal(String value, String rep) {
            this.value = value;
            this.rep = rep;
        }
    }

    private enum Equipment {
        BODYWEIGHT("bodyweight"), GYM("gym");

        private final String value;

        Equipment(String value) {
            this.value = value;
        }
    }

    public record WorkoutPlan(String startDate, String goal, String equipment,
                              String weightGuidance, List<WorkoutPlanDay> days) {
    }

    public record WorkoutPlanDay(String date, String focus, List<Workout> workouts,
                                 String activity, int durationMinutes) {
    }
}
