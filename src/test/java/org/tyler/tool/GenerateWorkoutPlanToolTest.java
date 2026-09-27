package org.tyler.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateWorkoutPlanToolTest {

    private final GenerateWorkoutPlanTool tool = new GenerateWorkoutPlanTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generatesSevenConsecutiveDaysWithValidWorkoutEntries() throws Exception {
        JsonNode plan = mapper.readTree(tool.execute(arguments("2026-09-28", "general_fitness", "bodyweight")));
        JsonNode days = plan.get("days");

        assertEquals("2026-09-28", plan.get("startDate").asText());
        assertEquals(7, days.size());
        assertEquals("bodyweight", plan.get("equipment").asText());
        assertFalse(plan.get("weightGuidance").asText().isBlank());
        for (int index = 0; index < days.size(); index++) {
            JsonNode day = days.get(index);
            assertEquals(LocalDate.of(2026, 9, 28).plusDays(index).toString(), day.get("date").asText());
            for (JsonNode workout : day.get("workouts")) {
                assertFalse(workout.get("workoutName").asText().isBlank());
                assertTrue(workout.get("rep").asText().matches("[1-9]\\d*X[1-9]\\d*"));
                assertEquals(day.get("date").asText(), workout.get("workoutDate").asText());
                assertTrue(workout.get("weight").isNull());
            }
        }
        assertEquals(3, days.get(0).get("workouts").size());
        assertEquals(0, days.get(6).get("workouts").size());
        assertEquals("Rest", days.get(6).get("focus").asText());
    }

    @Test
    void adjustsExercisesAndRepetitionsForGoalAndEquipment() throws Exception {
        JsonNode strength = mapper.readTree(tool.execute(arguments("2026-09-28", "strength", "gym")));
        JsonNode muscleGain = mapper.readTree(tool.execute(arguments("2026-09-28", "muscle_gain", "gym")));

        assertEquals("Goblet squat", strength.get("days").get(0).get("workouts").get(0).get("workoutName").asText());
        assertEquals("4X6", strength.get("days").get(0).get("workouts").get(0).get("rep").asText());
        assertEquals("3X10", muscleGain.get("days").get(0).get("workouts").get(0).get("rep").asText());
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("not-json"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("[]"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments("2026-02-30", "strength", "gym")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments("2026-09-28", "unknown", "gym")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments("2026-09-28", "strength", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{\"startDate\":\"2026-09-28\"}"));
    }

    @Test
    void exposesAgentFunctionSchema() {
        assertEquals("generateWorkoutPlan", tool.name());
        assertNotNull(tool.toFunctionTool());
    }

    private static String arguments(String startDate, String goal, String equipment) {
        return "{\"startDate\":\"" + startDate + "\",\"goal\":\"" + goal
                + "\",\"equipment\":\"" + equipment + "\"}";
    }
}
