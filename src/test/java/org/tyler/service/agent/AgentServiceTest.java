package org.tyler.service.agent;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;
import org.tyler.model.chat.ChatMessage;
import org.tyler.service.chatHistory.IChatHistoryService;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.tool.ITool;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Characterizes the Java agent before migration using real SDK messages and a mocked provider.
 * No network requests or live credentials are needed. Edge cases deliberately describe current
 * behavior, including empty replies at the round limit and failures after a tool has completed.
 */
class AgentServiceTest {

    private static final String MODEL = "configured-test-model";
    private static final String MESSAGE = "Plan my week";

    private final IClientFactory clientFactory = mock(IClientFactory.class);
    private final OpenAIClient client = mock(OpenAIClient.class);
    private final ResponseService responses = mock(ResponseService.class);
    private final IChatHistoryService history = mock(IChatHistoryService.class);

    @BeforeEach
    void setUp() {
        when(clientFactory.getClient()).thenReturn(client);
        when(client.responses()).thenReturn(responses);
        when(history.get()).thenReturn(List.of());
    }

    @Test
    void sendsHistoryCurrentMessageConfiguredModelAndToolDefinitions() {
        ITool tool = tool("lookup");
        IWorkoutPlanService planService = mock(IWorkoutPlanService.class);
        ITool planTool = new GenerateWorkoutPlanTool(planService);
        List<ChatMessage> savedHistory = List.of(
                new ChatMessage("user", "Earlier question"),
                new ChatMessage("assistant", "Earlier reply"));
        when(history.get()).thenReturn(savedHistory);
        when(responses.create(any(ResponseCreateParams.class)))
                .thenReturn(response("response-1", message(text("Your plan"))));

        assertEquals("Your plan", service(tool, planTool).ask(MESSAGE));

        ResponseCreateParams request = requests(1).getFirst();
        // Preserve overridden parameter schemas as well as the default no-argument tool.
        assertRequestConfiguration(request, tool, planTool);
        assertTrue(request.previousResponseId().isEmpty());
        assertEquals(List.of(
                inputMessage(EasyInputMessage.Role.USER, "Earlier question"),
                inputMessage(EasyInputMessage.Role.ASSISTANT, "Earlier reply"),
                inputMessage(EasyInputMessage.Role.USER, MESSAGE)), input(request));
        assertEquals(2, savedHistory.size());
        verify(tool, never()).execute(any());
        verifyNoInteractions(planService);
        verify(clientFactory).getClient();
        assertSavedExchange(MESSAGE, "Your plan");
    }

    @ParameterizedTest(name = "extracts {0}")
    @MethodSource("replyCases")
    void extractsReplyAndSavesExactlyOneExchange(String scenario, Response response, String expected) {
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(response);

        assertEquals(expected, service().ask(MESSAGE));

        ResponseCreateParams request = requests(1).getFirst();
        assertEquals(List.of(inputMessage(EasyInputMessage.Role.USER, MESSAGE)), input(request));
        assertRequestConfiguration(request);
        assertSavedExchange(MESSAGE, expected);
    }

    static Stream<Arguments> replyCases() {
        return Stream.of(
                Arguments.of("text", response("plain", message(text("Hello\nCafé"))), "Hello\nCafé"),
                Arguments.of("refusal", response("refusal", message(refusal("Cannot help"))), "Cannot help"),
                Arguments.of("ordered text and refusals across messages", response("mixed",
                        message(text("First"), refusal("Refusal"), text("Last")),
                        message(text("Next"))), "FirstRefusalLastNext"),
                Arguments.of("empty output", response("empty"), ""),
                Arguments.of("empty message", response("empty-message", message()), ""));
    }

    @Test
    void executesOneToolWithUnmodifiedArgumentsAndLinksItsOutputToTheCall() {
        ITool tool = tool("lookup");
        String arguments = "{\"date\":\"2026-10-16\"}";
        String result = "{\"note\":\"Café\\nReady\"}";
        when(tool.execute(arguments)).thenReturn(result);
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(
                response("response-1", message(text("Intermediate text")),
                        functionCall("call-1", "lookup", arguments)),
                response("response-2", message(text("Final reply"))));

        assertEquals("Final reply", service(tool).ask(MESSAGE));

        List<ResponseCreateParams> requests = requests(2);
        assertContinuation(requests.get(1), "response-1", output("call-1", result));
        requests.forEach(request -> assertRequestConfiguration(request, tool));
        InOrder order = inOrder(responses, tool, history);
        order.verify(history).get();
        order.verify(responses).create(any(ResponseCreateParams.class));
        order.verify(tool).execute(arguments);
        order.verify(responses).create(any(ResponseCreateParams.class));
        order.verify(history).appendExchange(MESSAGE, "Final reply");
        verify(tool).execute(arguments);
        assertSavedExchange(MESSAGE, "Final reply");
    }

    @Test
    void executesMultipleCallsInResponseOrderAndChainsEachRound() {
        ITool first = tool("first");
        ITool second = tool("second");
        when(first.execute("{\"step\":1}")).thenReturn("first result");
        when(second.execute("{}")).thenReturn("second result");
        when(first.execute("{\"step\":2}")).thenReturn("third result");
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(
                response("response-1", functionCall("call-a", "first", "{\"step\":1}"),
                        functionCall("call-b", "second", "{}")),
                response("response-2", functionCall("call-c", "first", "{\"step\":2}")),
                response("response-3", message(text("Done"))));

        // Registration order must not replace the order chosen by the model.
        assertEquals("Done", service(second, first).ask(MESSAGE));

        List<ResponseCreateParams> requests = requests(3);
        assertContinuation(requests.get(1), "response-1",
                output("call-a", "first result"), output("call-b", "second result"));
        assertContinuation(requests.get(2), "response-2", output("call-c", "third result"));
        requests.forEach(request -> assertRequestConfiguration(request, second, first));
        InOrder order = inOrder(first, second, responses);
        order.verify(responses).create(any(ResponseCreateParams.class));
        order.verify(first).execute("{\"step\":1}");
        order.verify(second).execute("{}");
        order.verify(responses).create(any(ResponseCreateParams.class));
        order.verify(first).execute("{\"step\":2}");
        order.verify(responses).create(any(ResponseCreateParams.class));
        verify(first, times(2)).execute(any());
        verify(second).execute(any());
        assertSavedExchange(MESSAGE, "Done");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopsAfterFiveToolRoundsAndSavesTheLastResponseText(boolean finalReplyAvailable) {
        ITool tool = tool("repeat");
        when(tool.execute(any())).thenReturn("ok");
        Response last = finalReplyAvailable
                ? response("response-5", message(text("Finished on the fifth round")))
                : response("response-5", functionCall("call-5", "repeat", "5"));
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(
                response("response-0", functionCall("call-0", "repeat", "0")),
                response("response-1", functionCall("call-1", "repeat", "1")),
                response("response-2", functionCall("call-2", "repeat", "2")),
                response("response-3", functionCall("call-3", "repeat", "3")),
                response("response-4", functionCall("call-4", "repeat", "4")), last)
                .thenThrow(new AssertionError("The agent exceeded five continuation requests"));

        // Characterization: an unresolved sixth tool call produces an empty successful reply.
        String expected = finalReplyAvailable ? "Finished on the fifth round" : "";
        assertEquals(expected, service(tool).ask(MESSAGE));

        List<ResponseCreateParams> requests = requests(6);
        for (int round = 0; round < 5; round++) {
            verify(tool).execute(Integer.toString(round));
            assertContinuation(requests.get(round + 1), "response-" + round, output("call-" + round, "ok"));
        }
        verify(tool, times(5)).execute(any());
        verify(tool, never()).execute("5");
        assertSavedExchange(MESSAGE, expected);
    }

    @Test
    void rejectsUnknownToolWithoutContinuingOrSavingHistory() {
        ITool tool = tool("known");
        when(responses.create(any(ResponseCreateParams.class)))
                .thenReturn(response("response-1", functionCall("call-1", "unknown", "{}")));

        IllegalStateException failure = assertThrowsExactly(IllegalStateException.class,
                () -> service(tool).ask(MESSAGE));

        assertEquals("Unknown tool: unknown", failure.getMessage());
        requests(1);
        verify(tool, never()).execute(any());
        assertHistoryUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "{}"})
    void propagatesRealToolArgumentValidationWithoutCallingItsService(String arguments) {
        IWorkoutPlanService planService = mock(IWorkoutPlanService.class);
        ITool tool = new GenerateWorkoutPlanTool(planService);
        when(responses.create(any(ResponseCreateParams.class)))
                .thenReturn(response("response-1", functionCall("call-1", tool.name(), arguments)));

        IllegalArgumentException failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> service(tool).ask(MESSAGE));

        if (arguments.equals("not-json")) {
            assertTrue(failure.getMessage().startsWith("Failed to parse workout plan arguments:"));
            assertNotNull(failure.getCause());
        } else {
            assertEquals(arguments.equals("[]") ? "Workout plan arguments must be a JSON object"
                    : "Missing or invalid field: startDate", failure.getMessage());
        }
        verifyNoInteractions(planService);
        requests(1);
        assertHistoryUnchanged();
    }

    @ParameterizedTest(name = "translates {0}, after tool: {1}")
    @MethodSource("authenticationFailures")
    void translatesAuthenticationFailuresAndRetainsTheirCause(RuntimeException failure, boolean afterTool) {
        ITool tool = tool("lookup");
        when(tool.execute("{}")).thenReturn("done");
        stubProviderFailure(failure, afterTool);

        OpenAIKeyException actual = assertThrowsExactly(OpenAIKeyException.class,
                () -> service(tool).ask(MESSAGE));

        assertEquals("This API key is invalid or unavailable", actual.getMessage());
        assertSame(failure, actual.getCause());
        requests(afterTool ? 2 : 1);
        verify(tool, times(afterTool ? 1 : 0)).execute("{}");
        assertHistoryUnchanged();
    }

    static Stream<Arguments> authenticationFailures() {
        return Stream.of(false, true).flatMap(afterTool -> Stream.of(
                Arguments.of(UnauthorizedException.builder().headers(Headers.builder().build()).build(), afterTool),
                Arguments.of(PermissionDeniedException.builder().headers(Headers.builder().build()).build(), afterTool)));
    }

    @ParameterizedTest(name = "propagates {0}, after tool: {1}")
    @MethodSource("providerFailures")
    void propagatesOtherProviderFailuresWithoutApplicationRetry(RuntimeException failure, boolean afterTool) {
        ITool tool = tool("lookup");
        when(tool.execute("{}")).thenReturn("done");
        stubProviderFailure(failure, afterTool);

        assertSame(failure, assertThrowsExactly(failure.getClass(), () -> service(tool).ask(MESSAGE)));

        requests(afterTool ? 2 : 1);
        verify(tool, times(afterTool ? 1 : 0)).execute("{}");
        assertHistoryUnchanged();
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(false, true).flatMap(afterTool -> Stream.of(
                Arguments.of(RateLimitException.builder().headers(Headers.builder().build()).build(), afterTool),
                Arguments.of(InternalServerException.builder().statusCode(500)
                        .headers(Headers.builder().build()).build(), afterTool)));
    }

    @ParameterizedTest
    @MethodSource("toolFailures")
    void propagatesToolFailuresWithoutReplayingCompletedToolsOrRunningLaterCalls(RuntimeException failure) {
        ITool first = tool("first");
        ITool failing = tool("failing");
        ITool later = tool("later");
        when(first.execute("{}")).thenReturn("saved");
        when(failing.execute("{}")).thenThrow(failure);
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(response("response-1",
                functionCall("call-1", "first", "{}"), functionCall("call-2", "failing", "{}"),
                functionCall("call-3", "later", "{}")));

        assertSame(failure, assertThrowsExactly(failure.getClass(), () -> service(first, failing, later).ask(MESSAGE)));

        InOrder order = inOrder(first, failing);
        order.verify(first).execute("{}");
        order.verify(failing).execute("{}");
        verify(first).execute("{}");
        verify(failing).execute("{}");
        verify(later, never()).execute(any());
        requests(1);
        assertHistoryUnchanged();
    }

    static Stream<RuntimeException> toolFailures() {
        return Stream.of(
                new IllegalArgumentException("Invalid tool input"),
                new SQLReadException("Read failed", new SQLException("Read unavailable")),
                new SQLPersistentException("Write failed", new SQLException("Write unavailable")),
                new IllegalStateException("Tool result could not be serialized"));
    }

    @Test
    void propagatesFactoryFailureBeforeReadingHistoryOrCallingProvider() {
        OpenAIKeyException failure = new OpenAIKeyException("OpenAI API key is empty; chat is unavailable");
        when(clientFactory.getClient()).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(OpenAIKeyException.class, () -> service().ask(MESSAGE)));

        verify(clientFactory).getClient();
        verifyNoInteractions(client, responses, history);
    }

    @Test
    void propagatesHistoryReadFailureBeforeCallingProvider() {
        IllegalStateException failure = new IllegalStateException("History unavailable");
        when(history.get()).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> service().ask(MESSAGE)));

        verifyNoInteractions(responses);
        assertHistoryUnchanged();
    }

    @Test
    void propagatesHistoryWriteFailureWithoutRetryingTheReplyOrCompletedTool() {
        ITool tool = tool("lookup");
        when(tool.execute("{}")).thenReturn("saved");
        when(responses.create(any(ResponseCreateParams.class))).thenReturn(
                response("response-1", functionCall("call-1", "lookup", "{}")),
                response("response-2", message(text("Done"))));
        IllegalStateException failure = new IllegalStateException("History could not be saved");
        when(history.appendExchange(MESSAGE, "Done")).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> service(tool).ask(MESSAGE)));

        requests(2);
        verify(tool).execute("{}");
        // The exchange was attempted once, but the call must not report a successful reply.
        assertSavedExchange(MESSAGE, "Done");
    }

    @Test
    void obtainsCurrentClientAndFreshHistoryForEachConversationRequest() {
        OpenAIClient replacementClient = mock(OpenAIClient.class);
        ResponseService replacementResponses = mock(ResponseService.class);
        when(clientFactory.getClient()).thenReturn(client, replacementClient);
        when(replacementClient.responses()).thenReturn(replacementResponses);
        when(history.get()).thenReturn(List.of(), List.of(
                new ChatMessage("user", "First question"), new ChatMessage("assistant", "First reply")));
        when(responses.create(any(ResponseCreateParams.class)))
                .thenReturn(response("response-1", message(text("First reply"))));
        when(replacementResponses.create(any(ResponseCreateParams.class)))
                .thenReturn(response("response-2", message(text("Second reply"))));
        AgentService service = service();

        assertEquals("First reply", service.ask("First question"));
        assertEquals("Second reply", service.ask("Second question"));

        requests(1);
        ArgumentCaptor<ResponseCreateParams> secondRequest = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(replacementResponses).create(secondRequest.capture());
        assertTrue(secondRequest.getValue().previousResponseId().isEmpty());
        assertEquals(List.of(inputMessage(EasyInputMessage.Role.USER, "First question"),
                inputMessage(EasyInputMessage.Role.ASSISTANT, "First reply"),
                inputMessage(EasyInputMessage.Role.USER, "Second question")), input(secondRequest.getValue()));
        verify(clientFactory, times(2)).getClient();
        InOrder order = inOrder(history);
        order.verify(history).get();
        order.verify(history).appendExchange("First question", "First reply");
        order.verify(history).get();
        order.verify(history).appendExchange("Second question", "Second reply");
        verifyNoMoreInteractions(history);
    }

    private AgentService service(ITool... tools) {
        return new AgentService(clientFactory, MODEL, List.of(tools), history);
    }

    private static ITool tool(String name) {
        ITool tool = mock(ITool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn("Test operation " + name);
        when(tool.toFunctionTool()).thenCallRealMethod();
        return tool;
    }

    private void stubProviderFailure(RuntimeException failure, boolean afterTool) {
        if (afterTool) {
            when(responses.create(any(ResponseCreateParams.class))).thenReturn(
                    response("response-1", functionCall("call-1", "lookup", "{}"))).thenThrow(failure);
        } else {
            when(responses.create(any(ResponseCreateParams.class))).thenThrow(failure);
        }
    }

    private List<ResponseCreateParams> requests(int count) {
        ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responses, times(count)).create(captor.capture());
        verifyNoMoreInteractions(responses);
        return captor.getAllValues();
    }

    private static void assertRequestConfiguration(ResponseCreateParams request, ITool... tools) {
        assertEquals(ResponsesModel.ofString(MODEL), request.model().orElseThrow());
        assertEquals(Stream.of(tools).map(tool -> Tool.ofFunction(tool.toFunctionTool())).toList(),
                request.tools().orElseThrow());
    }

    private static List<ResponseInputItem> input(ResponseCreateParams request) {
        return request.input().orElseThrow().asResponse();
    }

    private static void assertContinuation(ResponseCreateParams request, String previousId,
                                           ResponseInputItem... outputs) {
        assertEquals(previousId, request.previousResponseId().orElseThrow());
        assertEquals(List.of(outputs), input(request));
    }

    private void assertSavedExchange(String message, String reply) {
        verify(history).get();
        verify(history).appendExchange(message, reply);
        verifyNoMoreInteractions(history);
    }

    private void assertHistoryUnchanged() {
        verify(history).get();
        verifyNoMoreInteractions(history);
    }

    private static ResponseInputItem inputMessage(EasyInputMessage.Role role, String content) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(content).build());
    }

    private static ResponseInputItem output(String callId, String result) {
        return ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                .callId(callId).output(result).build());
    }

    private static ResponseOutputItem functionCall(String callId, String name, String arguments) {
        return ResponseOutputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                .callId(callId).name(name).arguments(arguments).build());
    }

    private static ResponseOutputMessage.Content text(String text) {
        return ResponseOutputMessage.Content.ofOutputText(ResponseOutputText.builder()
                .annotations(List.of()).text(text).build());
    }

    private static ResponseOutputMessage.Content refusal(String text) {
        return ResponseOutputMessage.Content.ofRefusal(ResponseOutputRefusal.builder().refusal(text).build());
    }

    private static ResponseOutputItem message(ResponseOutputMessage.Content... content) {
        return ResponseOutputItem.ofMessage(ResponseOutputMessage.builder()
                .id("message-1").status(ResponseOutputMessage.Status.COMPLETED).content(List.of(content)).build());
    }

    /** Complete SDK responses keep the fixture compatible with real response accessors. */
    private static Response response(String id, ResponseOutputItem... output) {
        return Response.builder().id(id).createdAt(0).model(MODEL)
                .error(Optional.empty()).incompleteDetails(Optional.empty()).instructions(Optional.empty())
                .metadata(Optional.empty()).output(List.of(output)).parallelToolCalls(false)
                .temperature(1.0).toolChoice(ToolChoiceOptions.AUTO).tools(List.of()).topP(1.0).build();
    }
}
