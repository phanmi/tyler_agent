package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.tyler.service.agent.bridge.handler.WorkoutPlanSaveHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

class WorkoutPlanSaveHandlerTest {
    @Test
    void delegatesSavingAndKeepsStorageCauses() throws Exception {
        var service = mock(org.tyler.service.workout.IWorkoutPlanService.class);
        var mapper = new OperationPayloadMapper();
        var expected = org.tyler.service.agent.python.WorkerTestSupport.fixture("plan_result").path("payload").path("result");
        var plan = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(expected.get("plan"), org.tyler.model.workout.WorkoutPlan.class);
        when(service.generateAndSave("2026-10-16", "strength", "gym")).thenReturn(plan);
        var handler = new WorkoutPlanSaveHandler(service, mapper);
        assertEquals(expected, handler.execute(input("plan")));
        var original = new org.tyler.exceptionHandler.exception.SQLPersistentException("write failed", new java.io.IOException("cause"));
        when(service.generateAndSave("2026-10-16", "strength", "gym")).thenThrow(original);
        assertSame(original, assertThrowsExactly(org.tyler.exceptionHandler.exception.SQLPersistentException.class,
                () -> handler.execute(input("plan"))));
        verify(service, never()).previewPlan(any(), any(), any());
    }
}
