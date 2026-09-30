package org.tyler.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.tyler.model.workout.WorkoutPlan;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies the adapter's parsing, delegation, serialization, and exception contracts. */
class GenerateWorkoutPlanToolTest {
    private final IWorkoutPlanService service = mock(IWorkoutPlanService.class);
    private final GenerateWorkoutPlanTool tool = new GenerateWorkoutPlanTool(service);
    private static final String ARGS = """
            {"startDate":"2026-09-28","goal":"strength","equipment":"gym"}
            """;

    @Test
    void delegatesSavingAndSerializesTheServiceResult() throws Exception {
        WorkoutPlan plan = new WorkoutPlan("2026-09-28", "strength", "gym", "guidance", List.of());
        when(service.generateAndSave("2026-09-28", "strength", "gym")).thenReturn(plan);
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.valueToTree(plan), mapper.readTree(tool.execute(ARGS)));
        verify(service).generateAndSave("2026-09-28", "strength", "gym");
        verifyNoMoreInteractions(service);
    }

    @Test
    void rejectsMalformedAndMissingArgumentsBeforeCallingService() {
        for (String args : List.of("not-json", "[]", "null", "{}", "{\"startDate\":4}", ARGS.replace("strength", " "))) {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
        }
        verifyNoInteractions(service);
    }

    @Test
    void propagatesServiceValidationAndPersistenceFailures() {
        IllegalArgumentException invalid = new IllegalArgumentException("Unsupported goal");
        SQLPersistentException failed = new SQLPersistentException("Write failed");
        when(service.generateAndSave("2026-09-28", "strength", "gym")).thenThrow(invalid).thenThrow(failed);
        assertSame(invalid, assertThrows(IllegalArgumentException.class, () -> tool.execute(ARGS)));
        assertSame(failed, assertThrows(SQLPersistentException.class, () -> tool.execute(ARGS)));
    }

    @Test
    void preservesFunctionSchema() {
        assertEquals("generateWorkoutPlan", tool.name());
        String definition = tool.toFunctionTool().toString();
        for (String field : List.of("startDate", "goal", "equipment", "general_fitness", "strength", "muscle_gain", "bodyweight", "gym")) {
            assertTrue(definition.contains(field));
        }
    }
}
