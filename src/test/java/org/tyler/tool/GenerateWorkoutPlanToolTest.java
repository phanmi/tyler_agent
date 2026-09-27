package org.tyler.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.dao.workout.WorkoutDAOSqlite;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.workout.Workout;
import org.tyler.service.workout.IWorkoutService;
import org.tyler.service.workout.WorkoutService;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerateWorkoutPlanToolTest {

    private final IWorkoutService workoutService = mock(IWorkoutService.class);
    private final GenerateWorkoutPlanTool tool = new GenerateWorkoutPlanTool(workoutService);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, Workout> saved = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong();

    @BeforeEach
    void setUp() {
        when(workoutService.getAllWorkouts()).thenAnswer(invocation -> new LinkedHashMap<>(saved));
        when(workoutService.saveWorkout(any())).thenAnswer(invocation -> {
            long id = nextId.incrementAndGet();
            saved.put(id, invocation.getArgument(0));
            return id;
        });
        when(workoutService.deleteWorkout(anyLong())).thenAnswer(invocation ->
                saved.remove(invocation.getArgument(0)) != null);
    }

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
                assertEquals(0, workout.get("weight").asInt());
            }
        }
        assertEquals(3, days.get(0).get("workouts").size());
        assertEquals(1, days.get(1).get("workouts").size());
        assertEquals(0, days.get(6).get("workouts").size());
        assertEquals("Rest", days.get(6).get("focus").asText());
        assertEquals(12, saved.size());
    }

    @Test
    void adjustsExercisesAndRepetitionsForGoalAndEquipment() throws Exception {
        var strength = tool.previewPlan("2026-09-28", "strength", "gym");
        var muscleGain = tool.previewPlan("2026-09-28", "muscle_gain", "gym");

        assertEquals("Goblet squat", strength.days().getFirst().workouts().getFirst().workoutName());
        assertEquals("4X6", strength.days().getFirst().workouts().getFirst().rep());
        assertEquals("3X10", muscleGain.days().getFirst().workouts().getFirst().rep());
        verifyNoInteractions(workoutService);
    }

    @Test
    void repeatedGenerationDoesNotDuplicateOrOverwriteSavedExercises() throws Exception {
        saved.put(7L, new Workout("Push-up", "4X8", new BigDecimal("15"), "2026-09-28"));
        nextId.set(7);

        JsonNode first = mapper.readTree(tool.execute(arguments("2026-09-28", "general_fitness", "bodyweight")));
        JsonNode second = mapper.readTree(tool.execute(arguments("2026-09-28", "general_fitness", "bodyweight")));

        assertEquals(12, saved.size());
        assertEquals("4X8", first.get("days").get(0).get("workouts").get(1).get("rep").asText());
        assertEquals(15, second.get("days").get(0).get("workouts").get(1).get("weight").asInt());
        verify(workoutService, times(11)).saveWorkout(any());
    }

    @Test
    void removesNewEntriesWhenSavingThePlanFails() {
        doAnswer(invocation -> {
            if (nextId.get() == 2) throw new IllegalStateException("Database write failed");
            long id = nextId.incrementAndGet();
            saved.put(id, invocation.getArgument(0));
            return id;
        }).when(workoutService).saveWorkout(any());

        assertThrows(IllegalStateException.class,
                () -> tool.execute(arguments("2026-09-28", "general_fitness", "bodyweight")));
        assertTrue(saved.isEmpty());
        verify(workoutService, times(2)).deleteWorkout(anyLong());
    }

    @Test
    void savesPlanToSqliteAndKeepsManualEditsOnRegeneration(@TempDir Path tempDir) throws Exception {
        IWorkoutService sqliteService = new WorkoutService(
                new WorkoutDAOSqlite(new FileSandbox(tempDir.toString()), "workouts.sqlite"));
        GenerateWorkoutPlanTool persistentTool = new GenerateWorkoutPlanTool(sqliteService);

        persistentTool.execute(arguments("2026-09-28", "general_fitness", "bodyweight"));
        assertEquals(12, sqliteService.getAllWorkouts().size());

        long pushUpId = sqliteService.getAllWorkouts().entrySet().stream()
                .filter(entry -> entry.getValue().workoutName().equals("Push-up"))
                .findFirst().orElseThrow().getKey();
        sqliteService.updateWorkout(pushUpId,
                new Workout("Push-up", "4X8", new BigDecimal("15"), "2026-09-28"));

        persistentTool.execute(arguments("2026-09-28", "general_fitness", "bodyweight"));
        assertEquals(12, sqliteService.getAllWorkouts().size());
        assertEquals("4X8", sqliteService.getWorkoutById(pushUpId).orElseThrow().rep());
        assertEquals(new BigDecimal("15"), sqliteService.getWorkoutById(pushUpId).orElseThrow().weight());
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
