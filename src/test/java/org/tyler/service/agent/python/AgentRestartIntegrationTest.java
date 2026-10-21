package org.tyler.service.agent.python;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.service.workout.IWorkoutService;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.python.PersistenceTestApplication.JSON;

/** Restarts the Python runtime against durable data without replaying committed writes. */
class AgentRestartIntegrationTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartPreservesIdsAndHistoryWithoutReplayingCommittedWrites(boolean failAfterWrite) throws Exception {
        String food;
        String workouts;
        String history;
        try (var python = new PersistenceTestApplication(workspace)) {
            python.chat(failAfterWrite ? "food_then_failure" : "food", failAfterWrite ? 500 : 200);
            python.chat("plan", 200);
            food = python.get("/api/food?date=2026-10-19").toString();
            workouts = JSON.writeValueAsString(python.context.getBean(IWorkoutService.class).getAllWorkouts());
            history = python.get("/api/agent/history").toString();
            assertEquals(1, python.gate.foodCalls.get());
        }

        try (var python = new PersistenceTestApplication(workspace)) {
            assertEquals(food, python.get("/api/food?date=2026-10-19").toString());
            assertEquals(workouts, JSON.writeValueAsString(python.context.getBean(IWorkoutService.class).getAllWorkouts()));
            assertEquals(history, python.get("/api/agent/history").toString());
            assertEquals(0, python.gate.foodCalls.get());
            python.chat("reply", 200);
            assertEquals(JSON.readTree(history).size() + 2, python.get("/api/agent/history").size());
        }
    }

}
