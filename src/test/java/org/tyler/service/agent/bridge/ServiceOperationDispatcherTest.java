package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.model.userInfo.UserInfo;
import org.tyler.model.workout.WorkoutPlan;
import org.tyler.service.agent.protocol.MessageValidator;
import org.tyler.service.agent.protocol.ProtocolException;
import org.tyler.service.food.IFoodService;
import org.tyler.service.userInfo.IUserInfoService;
import org.tyler.service.workout.IWorkoutPlanService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.python.WorkerTestSupport.fixture;

class ServiceOperationDispatcherTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IFoodService food = mock(IFoodService.class);
    private final IWorkoutPlanService plans = mock(IWorkoutPlanService.class);
    private final IUserInfoService profile = mock(IUserInfoService.class);
    private final IFileSandboxRead reader = mock(IFileSandboxRead.class);
    private final IFileSandboxWrite writer = mock(IFileSandboxWrite.class);
    private final ServiceOperationDispatcher dispatcher = BridgeTestSupport.dispatcher(food, plans, profile, reader, writer,
            Clock.fixed(Instant.parse("2026-10-16T16:34:56Z"), ZoneId.of("America/New_York")));

    @ParameterizedTest
    @ValueSource(strings = {"food", "plan", "profile", "file_read", "file_write", "time"})
    void allSixOperationsMatchSharedResultFixtures(String prefix) throws Exception {
        var request = fixture(prefix + "_request").path("payload");
        var expected = fixture(prefix + "_result");
        when(food.insertFood(any())).thenAnswer(call -> new FoodEntry(42, call.getArgument(0)));
        when(plans.generateAndSave(any(), any(), any())).thenReturn(
                MAPPER.treeToValue(fixture("plan_result").path("payload").path("result").path("plan"), WorkoutPlan.class));
        when(profile.get()).thenReturn(MAPPER.treeToValue(fixture("profile_result").path("payload")
                .path("result").path("profile"), UserInfo.class));
        when(reader.read("notes/session.txt")).thenReturn(fixture("file_read_result").path("payload")
                .path("result").path("content").textValue());
        JsonNode result = dispatcher.dispatch(request.path("operation").textValue(), request.path("input"));
        assertEquals(expected.path("payload").path("result"), result);
        ((ObjectNode) expected.path("payload")).set("result", result);
        new MessageValidator().validateMessage(expected);
        switch (prefix) {
            case "food" -> verify(food).insertFood(MAPPER.treeToValue(request.path("input").path("food"), Food.class));
            case "plan" -> verify(plans).generateAndSave("2026-10-16", "strength", "gym");
            case "profile" -> verify(profile).get();
            case "file_read" -> verify(reader).read("notes/session.txt");
            case "file_write" -> verify(writer).write("notes/session.txt", request.path("input").path("content").textValue());
            default -> { }
        }
        verifyNoMoreInteractions(food, plans, profile, reader, writer);
    }

    @ParameterizedTest
    @CsvSource({"amount, 0.00000000000000000100", "amount, -0.00", "calories, 2000000000.000"})
    void decimalsAndLongIdsNeverPassThroughFloatingPoint(String field, String number) {
        var input = input("food");
        ((ObjectNode) input.path("food").path("genericInfo")).put(field, number);
        when(food.insertFood(any())).thenAnswer(call -> new FoodEntry(Long.MAX_VALUE, call.getArgument(0)));
        var result = dispatcher.dispatch("food.insert", input);
        assertEquals(Long.toString(Long.MAX_VALUE), result.path("id").textValue());
        assertEquals(number.equals("-0.00") ? "0.00" : number,
                result.path("food").path("genericInfo").path(field).textValue());
    }

    @Test
    void nullableProfileComponentsRemainExplicitNulls() {
        when(profile.get()).thenReturn(new UserInfo(null, null));
        assertEquals("{\"profile\":{\"User\":null,\"Other\":null}}",
                dispatcher.dispatch("user_info.read", MAPPER.createObjectNode()).toString());
        when(profile.get()).thenReturn(null);
        assertThrowsExactly(IllegalStateException.class,
                () -> dispatcher.dispatch("user_info.read", MAPPER.createObjectNode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"recordFood", "GetUserInfo", "SQL.execute", "file.delete", ""})
    void unsupportedOperationsCannotReachDependencies(String operation) {
        assertEquals("UNKNOWN_OPERATION", assertThrowsExactly(ProtocolException.class,
                () -> dispatcher.dispatch(operation, MAPPER.createObjectNode())).code());
        verifyNoInteractions(food, plans, profile, reader, writer);
    }

    @Test
    void malformedInputsAreRejectedBeforeSideEffects() throws Exception {
        for (String json : new String[]{"null", "[]", "{}", "{\"path\":2,\"content\":\"text\"}",
                "{\"path\":\"notes.txt\",\"content\":null}",
                "{\"path\":\"notes.txt\",\"content\":\"text\",\"method\":\"delete\"}"}) {
            assertEquals("INVALID_MESSAGE", assertThrowsExactly(ProtocolException.class,
                    () -> dispatcher.dispatch("file.write", MAPPER.readTree(json))).code());
        }
        var foodInput = input("food");
        ((ObjectNode) foodInput.path("food").path("genericInfo")).put("amount", "2000000000.01");
        assertEquals("INVALID_VALUE", assertThrowsExactly(ProtocolException.class,
                () -> dispatcher.dispatch("food.insert", foodInput)).code());
        ((ObjectNode) foodInput.path("food").path("genericInfo")).put("amount", "1e2");
        assertEquals("INVALID_MESSAGE", assertThrowsExactly(ProtocolException.class,
                () -> dispatcher.dispatch("food.insert", foodInput)).code());
        assertEquals("INVALID_MESSAGE", assertThrowsExactly(ProtocolException.class,
                () -> dispatcher.dispatch(null, input("food"))).code());
        verifyNoInteractions(food, plans, profile, reader, writer);
    }

    @Test
    void storageFailuresRetainTheirIdentityAndNeverBecomeSuccess() {
        var sql = new SQLPersistentException("synthetic SQL failure", new java.io.IOException("cause"));
        when(food.insertFood(any())).thenThrow(sql);
        assertSame(sql, assertThrowsExactly(SQLPersistentException.class, () -> dispatcher.dispatch("food.insert", input("food"))));
        var read = new FileReadException("synthetic read failure");
        when(reader.read(anyString())).thenThrow(read);
        assertSame(read, assertThrowsExactly(FileReadException.class, () -> dispatcher.dispatch("file.read", input("file_read"))));
        var write = new FileWriteException("synthetic write failure");
        doThrow(write).when(writer).write(anyString(), anyString());
        assertSame(write, assertThrowsExactly(FileWriteException.class, () -> dispatcher.dispatch("file.write", input("file_write"))));
    }

    static ObjectNode input(String fixturePrefix) {
        return (ObjectNode) fixture(fixturePrefix + "_request").path("payload").path("input");
    }
}
