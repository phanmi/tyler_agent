package org.tyler.controller.workout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.model.workout.Workout;
import org.tyler.service.workout.IWorkoutService;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
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
        LocalDate date = LocalDate.of(2026, 9, 28);
        Map<Long, Workout> records = new LinkedHashMap<>();
        records.put(1L, new Workout("Squat", "4X12", new BigDecimal("20"), date.toString()));
        records.put(3L, new Workout("Push-up", "3X10", BigDecimal.ZERO, date.toString()));
        when(workoutService.getWorkoutsByDate(date)).thenReturn(records);

        mockMvc.perform(get("/api/workouts").param("date", "2026-09-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].workout.workoutName").value("Squat"))
                .andExpect(jsonPath("$[0].workout.rep").value("4X12"))
                .andExpect(jsonPath("$[0].workout.weight").value(20))
                .andExpect(jsonPath("$[0].workout.workoutDate").value("2026-09-28"))
                .andExpect(jsonPath("$[1].id").value(3));
        verify(workoutService).getWorkoutsByDate(date);
        verifyNoMoreInteractions(workoutService);
    }

    @Test
    void rejectsInvalidDate() throws Exception {
        for (String invalidDate : new String[]{"bad-date", "2026-02-30", "", "  "}) {
            mockMvc.perform(get("/api/workouts").param("date", invalidDate))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("date must use the format YYYY-MM-DD"));
        }
        verifyNoInteractions(workoutService);
    }

    @Test
    void dateWithoutRecordsReturnsEmptyArray() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 28);
        when(workoutService.getWorkoutsByDate(date)).thenReturn(Map.of());
        mockMvc.perform(get("/api/workouts").param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(workoutService).getWorkoutsByDate(date);
        verifyNoMoreInteractions(workoutService);
    }

    @Test
    void dateQueryFailureRetainsGenericServerError() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 28);
        when(workoutService.getWorkoutsByDate(date)).thenThrow(new SQLReadException("Internal storage detail"));
        mockMvc.perform(get("/api/workouts").param("date", date.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error. Please try again later"));
        verify(workoutService).getWorkoutsByDate(date);
        verifyNoMoreInteractions(workoutService);
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
