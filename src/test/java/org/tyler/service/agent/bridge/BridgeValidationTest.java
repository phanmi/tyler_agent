package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.dao.foodrecord.IFoodRecordDAO;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.food.Food;
import org.tyler.model.workout.WorkoutPlan;
import org.springframework.transaction.support.TransactionCallback;
import org.tyler.service.food.FoodService;
import org.tyler.service.userInfo.IUserInfoService;
import org.tyler.service.workout.IWorkoutService;
import org.tyler.service.workout.WorkoutPlanService;
import org.tyler.tool.foodRecordTool.RecordFoodTool;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;
import org.tyler.validation.FoodValidator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

/** The bridge and legacy adapters reach real shared validators, before any storage or transaction. */
class BridgeValidationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IFoodRecordDAO dao = mock(IFoodRecordDAO.class);
    private final FoodService food = new FoodService(dao, new FoodValidator());
    private final IWorkoutService workouts = mock(IWorkoutService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final WorkoutPlanService plans = new WorkoutPlanService(workouts, transactions);
    private final ServiceOperationDispatcher dispatcher = BridgeTestSupport.dispatcher(food, plans,
            mock(IUserInfoService.class), mock(IFileSandboxRead.class), mock(IFileSandboxWrite.class), Clock.systemUTC());

    static Stream<ObjectNode> invalidFood() {
        List<ObjectNode> cases = new ArrayList<>();
        for (String parent : List.of("genericInfo", "macroNutrients")) {
            var missing = input("food");
            ((ObjectNode) missing.path("food")).putNull(parent);
            cases.add(missing);
            List<String> numbers = parent.equals("genericInfo") ? List.of("amount", "calories")
                    : List.of("protein", "carbs", "fat", "fiber");
            for (String field : numbers) {
                for (String value : new String[]{null, "-0.01"}) {
                    var invalid = input("food");
                    ((ObjectNode) invalid.path("food").path(parent)).put(field, value);
                    cases.add(invalid);
                }
            }
        }
        for (String field : List.of("foodName", "unit", "date")) {
            for (String value : new String[]{null, "", " "}) {
                var invalid = input("food");
                ((ObjectNode) invalid.path("food").path("genericInfo")).put(field, value);
                cases.add(invalid);
            }
        }
        for (String date : List.of("2026-02-30", "2026-1-01", "2026-10-16T00:00:00", " 2026-10-16")) {
            var invalid = input("food");
            ((ObjectNode) invalid.path("food").path("genericInfo")).put("date", date);
            cases.add(invalid);
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidFood")
    void foodDomainErrorsMatchLegacyServicePath(ObjectNode invalid) throws Exception {
        Food value = MAPPER.treeToValue(invalid.path("food"), Food.class);
        var expected = assertThrowsExactly(IllegalArgumentException.class,
                () -> new RecordFoodTool(food).execute(MAPPER.writeValueAsString(value)));
        var actual = assertThrowsExactly(IllegalArgumentException.class, () -> dispatcher.dispatch("food.insert", invalid));
        assertEquals(expected.getMessage(), actual.getMessage());
        verifyNoInteractions(dao);
    }

    static Stream<ObjectNode> invalidPlans() {
        List<ObjectNode> cases = new ArrayList<>();
        for (String field : List.of("startDate", "goal", "equipment")) {
            for (String value : new String[]{null, "", "unknown"}) cases.add(input("plan").put(field, value));
        }
        for (String value : List.of("2026-02-30", "2026-1-01", " 2026-10-16",
                LocalDate.MAX.toString(), LocalDate.MAX.minusDays(5).toString())) {
            cases.add(input("plan").put("startDate", value));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidPlans")
    void planDomainErrorsMatchLegacyServicePath(ObjectNode invalid) {
        var expected = assertThrowsExactly(IllegalArgumentException.class,
                () -> new GenerateWorkoutPlanTool(plans).execute(invalid.toString()));
        var actual = assertThrowsExactly(IllegalArgumentException.class,
                () -> dispatcher.dispatch("workout_plan.generate_save", invalid));
        assertEquals(expected.getMessage(), actual.getMessage());
        verifyNoInteractions(workouts, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+10000-01-01", "+999999999-12-25"})
    void expandedDatesAndLastRepresentableWeekReachSharedService(String date) {
        when(workouts.getAllWorkouts()).thenReturn(java.util.Map.of());
        when(transactions.execute(org.mockito.ArgumentMatchers.<TransactionCallback<WorkoutPlan>>any()))
                .thenAnswer(call -> {
                    TransactionCallback<WorkoutPlan> callback = call.getArgument(0);
                    return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
                });
        var result = dispatcher.dispatch("workout_plan.generate_save", input("plan").put("startDate", date));
        assertEquals(date, result.path("plan").path("startDate").textValue());
        assertEquals(LocalDate.parse(date).plusDays(6).toString(), result.path("plan").path("days").get(6).path("date").textValue());
    }
}
