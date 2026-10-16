package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.tyler.controller.food.FoodController;
import org.tyler.controller.workout.WorkoutController;
import org.tyler.service.food.IFoodService;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.service.workout.IWorkoutService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real Spring argument binding, which must reject input before service invocation. */
class RequestBindingExceptionTest {
    private final IFoodService food = mock(IFoodService.class);
    private final IWorkoutService workouts = mock(IWorkoutService.class);
    private final IWorkoutPlanService plans = mock(IWorkoutPlanService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new FoodController(food), new WorkoutController(workouts, plans))
            .setControllerAdvice(new GenericExceptionHandler()).build();

    @ParameterizedTest
    @ValueSource(strings = {"startDate", "goal", "equipment"})
    void missingPlanParametersReturn400BeforeServiceInvocation(String missing) throws Exception {
        var request = get("/api/workouts/plan");
        if (!missing.equals("startDate")) request.param("startDate", "2026-10-16");
        if (!missing.equals("goal")) request.param("goal", "strength");
        if (!missing.equals("equipment")) request.param("equipment", "gym");
        var result = mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing required parameter: " + missing)).andReturn();
        assertInstanceOf(MissingServletRequestParameterException.class, result.getResolvedException());
        verifyNoInteractions(food, workouts, plans);
    }

    @Test
    void missingWorkoutDateReturns400BeforeServiceInvocation() throws Exception {
        mvc.perform(get("/api/workouts")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing required parameter: date"));
        verifyNoInteractions(food, workouts, plans);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "1.5", "9223372036854775808", "-9223372036854775809"})
    void invalidLongIdsReturn400ForDeletesAndUpdates(String id) throws Exception {
        for (var request : List.of(delete("/api/food/" + id), delete("/api/workouts/" + id),
                put("/api/workouts/" + id).contentType("application/json").content("""
                        {"workoutName":"Squat","rep":"4X12","weight":0,"workoutDate":"2026-10-16"}
                        """))) {
            var result = mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Invalid format or value for parameter: id")).andReturn();
            assertInstanceOf(MethodArgumentTypeMismatchException.class, result.getResolvedException());
        }
        verifyNoInteractions(food, workouts, plans);
    }
}
