package org.tyler.controller.workout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.model.workout.Workout;
import org.tyler.service.workout.IWorkoutService;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkoutControllerTest {

    private MockMvc mockMvc;
    private IWorkoutService workoutService;

    private static final String WORKOUT_JSON = """
            {"workoutName":"Squat","rep":"4X12","weight":20,"workoutDate":"2026-09-28"}
            """;

    @BeforeEach
    void setUp() {
        workoutService = mock(IWorkoutService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkoutController(workoutService, new GenerateWorkoutPlanTool(workoutService)))
                .setControllerAdvice(new GenericExceptionHandler())
                .build();
    }

    @Test
    void returnsOnlyWorkoutsForSelectedDate() throws Exception {
        when(workoutService.getAllWorkouts()).thenReturn(Map.of(
                1L, new Workout("Squat", "4X12", new BigDecimal("20"), "2026-09-28"),
                2L, new Workout("Push-up", "3X10", BigDecimal.ZERO, "2026-09-29")));

        mockMvc.perform(get("/api/workouts").param("date", "2026-09-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].workout.workoutName").value("Squat"));
    }

    @Test
    void rejectsInvalidDate() throws Exception {
        mockMvc.perform(get("/api/workouts").param("date", "bad-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsAndUpdatesWorkout() throws Exception {
        when(workoutService.saveWorkout(new Workout("Squat", "4X12", new BigDecimal("20"), "2026-09-28")))
                .thenReturn(7L);
        when(workoutService.updateWorkout(7L,
                new Workout("Squat", "4X12", new BigDecimal("20"), "2026-09-28")))
                .thenReturn(true);

        mockMvc.perform(post("/api/workouts").contentType("application/json").content(WORKOUT_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));
        mockMvc.perform(put("/api/workouts/7").contentType("application/json").content(WORKOUT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workout.rep").value("4X12"));
        verify(workoutService).updateWorkout(7L,
                new Workout("Squat", "4X12", new BigDecimal("20"), "2026-09-28"));
    }

    @Test
    void returnsNotFoundForMissingUpdateOrDelete() throws Exception {
        mockMvc.perform(put("/api/workouts/9").contentType("application/json").content(WORKOUT_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/workouts/9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesSavedWorkout() throws Exception {
        when(workoutService.deleteWorkout(7L)).thenReturn(true);

        mockMvc.perform(delete("/api/workouts/7"))
                .andExpect(status().isNoContent());
        verify(workoutService).deleteWorkout(7L);
    }

    @Test
    void returnsSevenDayPlan() throws Exception {
        mockMvc.perform(get("/api/workouts/plan")
                        .param("startDate", "2026-09-28")
                        .param("goal", "general_fitness")
                        .param("equipment", "bodyweight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].workouts[0].rep").value("3X12"))
                .andExpect(jsonPath("$.days[6].focus").value("Rest"));
    }
}
