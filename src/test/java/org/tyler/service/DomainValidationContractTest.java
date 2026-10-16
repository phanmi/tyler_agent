package org.tyler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.tyler.controller.food.FoodController;
import org.tyler.controller.workout.WorkoutController;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.dao.workout.IWorkoutDAO;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.exceptionHandler.SqlExceptionHandler;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.model.food.Food;
import org.tyler.model.workout.Workout;
import org.tyler.service.food.FoodService;
import org.tyler.service.workout.WorkoutPlanService;
import org.tyler.service.workout.WorkoutService;
import org.tyler.tool.foodRecordTool.RecordFoodTool;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real services behind adapters: invalid domains never reach the mocked persistence boundary. */
class DomainValidationContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FOOD = """
            {"genericInfo":{"foodName":"Apple","amount":1,"unit":"piece","calories":0,"date":"2026-10-16"},
             "macroNutrients":{"protein":0,"carbs":0,"fat":0,"fiber":0}}
            """;
    private static final String WORKOUT = """
            {"workoutName":"Squat","rep":"4X12","weight":0,"workoutDate":"2026-10-16"}
            """;
    private final IFoodRecordDAO foodDao = mock(IFoodRecordDAO.class);
    private final IWorkoutDAO workoutDao = mock(IWorkoutDAO.class);
    private final FoodService foodService = new FoodService(foodDao);
    private final WorkoutService workoutService = new WorkoutService(workoutDao);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final WorkoutPlanService planService = new WorkoutPlanService(workoutService, transactions);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FoodController(foodService),
                    new WorkoutController(workoutService, planService))
            .setControllerAdvice(new SqlExceptionHandler(), new GenericExceptionHandler()).build();

    private static String changed(String json, String parent, String field, Object value) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        ObjectNode target = parent == null ? root : (ObjectNode) root.get(parent);
        target.set(field, MAPPER.valueToTree(value));
        return root.toString();
    }

    static Stream<String> invalidFood() throws Exception {
        List<String> cases = new ArrayList<>(List.of("null", "{}"));
        for (String object : List.of("genericInfo", "macroNutrients")) {
            cases.add(changed(FOOD, null, object, null));
        }
        for (String field : List.of("foodName", "unit")) {
            for (String value : new String[]{null, "", " "}) {
                cases.add(changed(FOOD, "genericInfo", field, value));
            }
        }
        for (String date : invalidDates().toList()) {
            cases.add(changed(FOOD, "genericInfo", "date", date));
        }
        for (String field : List.of("amount", "calories", "protein", "carbs", "fat", "fiber")) {
            String parent = List.of("amount", "calories").contains(field) ? "genericInfo" : "macroNutrients";
            cases.add(changed(FOOD, parent, field, null));
            cases.add(changed(FOOD, parent, field, new BigDecimal("-0.01")));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidFood")
    void foodToolAndDirectServiceUseTheSameDomainErrors(String json) throws Exception {
        Food food = MAPPER.readValue(json, Food.class);
        var direct = assertThrowsExactly(IllegalArgumentException.class, () -> foodService.insertFood(food));
        var tool = assertThrowsExactly(IllegalArgumentException.class,
                () -> new RecordFoodTool(foodService).execute(json));
        assertEquals(direct.getMessage(), tool.getMessage());
        verifyNoInteractions(foodDao);
    }

    static Stream<String> invalidDates() {
        return Stream.of(null, "", " ", "not-a-date", "2026-02-29", "2026-02-30", "2026-1-01",
                "2026-10-16T12:00:00", " 2026-10-16");
    }

    @ParameterizedTest
    @MethodSource("invalidDates")
    void foodDatesAreConsistentForToolLookupAndDateDeletion(String date) throws Exception {
        var expected = assertThrowsExactly(IllegalArgumentException.class,
                () -> foodService.getFoodRecordsByDate(date));
        assertEquals(expected.getMessage(), assertThrowsExactly(IllegalArgumentException.class,
                () -> foodService.deleteFoodByDate(date)).getMessage());
        String json = changed(FOOD, "genericInfo", "date", date);
        assertEquals(expected.getMessage(), assertThrowsExactly(IllegalArgumentException.class,
                () -> new RecordFoodTool(foodService).execute(json)).getMessage());
        var request = get("/api/food");
        if (date != null) request.param("date", date);
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(expected.getMessage()));
        verifyNoInteractions(foodDao);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0001", "123456789.123456789"})
    void foodAllowsZeroAndPositiveDecimalValuesWithoutChangingUnits(String number) throws Exception {
        String json = FOOD;
        for (String field : List.of("amount", "calories", "protein", "carbs", "fat", "fiber")) {
            json = changed(json, List.of("amount", "calories").contains(field) ? "genericInfo" : "macroNutrients",
                    field, new BigDecimal(number));
        }
        Food expected = MAPPER.readValue(json, Food.class);
        when(foodDao.insert(expected)).thenReturn(7L);
        String result = new RecordFoodTool(foodService).execute(json);
        assertEquals(expected, MAPPER.readValue(result, Food.class));
        verify(foodDao).insert(expected);
    }

    static Stream<String> invalidWorkouts() throws Exception {
        List<String> cases = new ArrayList<>();
        for (String name : new String[]{null, "", " "}) cases.add(changed(WORKOUT, null, "workoutName", name));
        for (String date : invalidDates().toList()) cases.add(changed(WORKOUT, null, "workoutDate", date));
        cases.add(changed(WORKOUT, null, "weight", null));
        cases.add(changed(WORKOUT, null, "weight", new BigDecimal("-0.01")));
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidWorkouts")
    void workoutHttpAndServiceRejectTheSameInvalidWrites(String json) throws Exception {
        Workout workout = MAPPER.readValue(json, Workout.class);
        var expected = assertThrowsExactly(IllegalArgumentException.class, () -> workoutService.saveWorkout(workout));
        assertEquals(expected.getMessage(), assertThrowsExactly(IllegalArgumentException.class,
                () -> workoutService.updateWorkout(1, workout)).getMessage());
        for (var request : List.of(post("/api/workouts"), put("/api/workouts/1"))) {
            mvc.perform(request.contentType("application/json").content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(expected.getMessage()));
        }
        verifyNoInteractions(workoutDao);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidIdsNeverReachPersistence(long id) throws Exception {
        Workout valid = MAPPER.readValue(WORKOUT, Workout.class);
        assertThrowsExactly(IllegalArgumentException.class, () -> foodService.deleteFoodById(id));
        assertThrowsExactly(IllegalArgumentException.class, () -> workoutService.getWorkoutById(id));
        assertThrowsExactly(IllegalArgumentException.class, () -> workoutService.updateWorkout(id, valid));
        assertThrowsExactly(IllegalArgumentException.class, () -> workoutService.deleteWorkout(id));
        for (String route : List.of("/api/food/", "/api/workouts/")) {
            mvc.perform(delete(route + id)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("id must be greater than 0"));
        }
        verifyNoInteractions(foodDao, workoutDao);
    }

    static Stream<String> invalidPlans() throws Exception {
        String valid = "{\"startDate\":\"2026-10-16\",\"goal\":\"strength\",\"equipment\":\"gym\"}";
        List<String> cases = new ArrayList<>();
        for (String field : List.of("startDate", "goal", "equipment")) {
            for (String value : new String[]{null, "", " ", "invalid"}) cases.add(changed(valid, null, field, value));
            ObjectNode missing = (ObjectNode) MAPPER.readTree(valid);
            missing.remove(field);
            cases.add(missing.toString());
        }
        cases.add(changed(valid, null, "startDate", "2026-02-30"));
        cases.add(changed(valid, null, "startDate", "+999999999-12-31"));
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidPlans")
    void planToolAndHttpPreviewShareValidationBeforeTransactions(String json) throws Exception {
        var args = MAPPER.readTree(json);
        String date = args.path("startDate").asText(null);
        String goal = args.path("goal").asText(null);
        String equipment = args.path("equipment").asText(null);
        var expected = assertThrowsExactly(IllegalArgumentException.class,
                () -> new WorkoutController(workoutService, planService).workoutPlan(date, goal, equipment));
        assertEquals(expected.getMessage(), assertThrowsExactly(IllegalArgumentException.class,
                () -> new GenerateWorkoutPlanTool(planService).execute(json)).getMessage());
        // Required query-parameter absence is an HTTP binding error; supplied values reach the service.
        if (date != null && goal != null && equipment != null) {
            mvc.perform(get("/api/workouts/plan").param("startDate", date).param("goal", goal).param("equipment", equipment))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(expected.getMessage()));
        }
        verifyNoInteractions(workoutDao, transactions);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "4x12", "0X12", "4X0", "-1X12"})
    void modelRepInvariantRemainsAClientError(String rep) throws Exception {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> new Workout("Squat", rep, BigDecimal.ZERO, "2026-10-16"));
        mvc.perform(post("/api/workouts").contentType("application/json").content(changed(WORKOUT, null, "rep", rep)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(workoutDao);
    }

    @Test
    void storageFailuresRemainDistinctAndMapToServerErrors() throws Exception {
        SQLPersistentException write = new SQLPersistentException("Storage write failed");
        SQLReadException read = new SQLReadException("Storage read failed");
        Workout valid = MAPPER.readValue(WORKOUT, Workout.class);
        when(workoutDao.insert(valid)).thenThrow(write);
        when(foodDao.loadRecordsByDate("2026-10-16")).thenThrow(read);
        assertSame(write, assertThrows(SQLPersistentException.class, () -> workoutService.saveWorkout(valid)));
        assertSame(read, assertThrows(SQLReadException.class, () -> foodService.getFoodRecordsByDate("2026-10-16")));
        mvc.perform(post("/api/workouts").contentType("application/json").content(WORKOUT))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error")
                        .value("Database operation failed. Please try again later"));
        mvc.perform(get("/api/food").param("date", "2026-10-16"))
                .andExpect(status().isInternalServerError());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0001", "123456789.123456789"})
    void workoutWritesAcceptNonnegativeDecimalsAndLeapDates(String number) throws Exception {
        Workout valid = new Workout("Squat", "4X12", new BigDecimal(number), "2024-02-29");
        when(workoutDao.insert(valid)).thenReturn(7L);
        mvc.perform(post("/api/workouts").contentType("application/json").content(MAPPER.writeValueAsString(valid)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.workout.workoutDate").value("2024-02-29"));
        verify(workoutDao).insert(valid);
        String foodJson = changed(FOOD, "genericInfo", "date", "2024-02-29");
        Food food = MAPPER.readValue(foodJson, Food.class);
        when(foodDao.insert(food)).thenReturn(8L);
        assertEquals(8L, foodService.insertFood(food).id());
    }
}
