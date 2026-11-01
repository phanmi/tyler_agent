package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.tyler.service.apiKey.IApiKeyService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.python.PersistenceTestApplication.*;
import static org.tyler.service.agent.python.WorkerTestSupport.await;

/** Full HTTP -> Python model loop -> Java services -> temporary SQLite/history integration. */
class AgentPersistenceIntegrationTest {
    @TempDir Path workspace;
    private static final String FOOD_DATE = "/api/food?date=2026-10-19";
    private static final String HISTORY = "/api/agent/history";
    private static final String PREVIEW = "/api/workouts/plan?startDate=2026-10-19&goal=general_fitness&equipment=bodyweight";

    @Test
    void equalFoodEntriesAppendHaveStableIdsAndSupportCalendarDeletion() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            JsonNode output = JSON.readTree(reply(app.chat("food", 200)).path("outputs").get(0).asText());
            JsonNode first = app.get(FOOD_DATE).get(0);
            assertEquals(first.path("food"), output);
            app.chat("food", 200);
            JsonNode records = app.get(FOOD_DATE);
            assertEquals(2, records.size());
            assertEquals(first, records.get(0));
            assertNotEquals(first.path("id"), records.get(1).path("id"));
            assertEquals(first.path("food"), records.get(1).path("food"));
            assertEquals(4, app.get(HISTORY).size());
            assertEquals(app.get(HISTORY), JSON.readTree(Files.readString(workspace.resolve("chat-history.json"))));
            assertTrue(app.http("DELETE", "/api/food/" + first.path("id").asLong(), null, 200).asBoolean());
            assertEquals(records.get(1), app.get(FOOD_DATE).get(0));
            assertTrue(app.http("DELETE", "/api/food/date/2026-10-19", null, 200).asBoolean());
            assertTrue(app.get(FOOD_DATE).isEmpty());
        }
    }

    @Test
    void planPreviewIsReadOnlyAndRepeatedSavePreservesIdsAndManualEdits() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            JdbcTemplate jdbc = jdbc(app);
            assertEquals(7, app.get(PREVIEW).path("days").size());
            assertEquals(0, count(jdbc));
            JsonNode savedPlan = JSON.readTree(reply(app.chat("plan", 200)).path("outputs").get(0).asText());
            assertEquals(app.get(PREVIEW), savedPlan);
            assertEquals(12, count(jdbc));
            JsonNode record = app.get("/api/workouts?date=2026-10-19").get(0);
            var edited = record.path("workout").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) edited).put("rep", "4X8").put("weight", 15.5);
            app.http("PUT", "/api/workouts/" + record.path("id").asLong(), edited.toString(), 200);
            var snapshot = jdbc.queryForList("SELECT * FROM workout_record ORDER BY id");
            app.chat("plan", 200);
            assertEquals(snapshot, jdbc.queryForList("SELECT * FROM workout_record ORDER BY id"));
            assertEquals(edited, app.get("/api/workouts?date=2026-10-19").get(0).path("workout"));
            app.get(PREVIEW);
            assertEquals(snapshot, jdbc.queryForList("SELECT * FROM workout_record ORDER BY id"));
            assertEquals(4, app.get(HISTORY).size());
        }
    }

    @Test
    void planInsertFailureRollsBackEarlierInsertsAndPreservesPreexistingWorkouts() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            app.http("POST", "/api/workouts", """
                    {"workoutName":"User exercise","rep":"4X8","weight":15.5,"workoutDate":"2026-10-19"}
                    """, 201);
            JdbcTemplate jdbc = jdbc(app);
            var before = jdbc.queryForList("SELECT * FROM workout_record ORDER BY id");
            try (var sql = getClass().getResourceAsStream("/agent-integration/fail-plan.sql")) {
                assertNotNull(sql);
                jdbc.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
            }
            // The trigger fails only after the pre-existing row plus two plan rows are visible
            // inside the transaction. A new connection after HTTP failure must see neither insert.
            JsonNode error = app.chat("plan", 500);
            assertEquals("Database operation failed. Please try again later", error.path("error").asText());
            assertEquals(before, jdbc.queryForList("SELECT * FROM workout_record ORDER BY id"));
            assertTrue(app.get(HISTORY).isEmpty());
            jdbc.execute("DROP TRIGGER fail_integration_plan");
            app.chat("plan", 200);
            assertEquals(13, count(jdbc));
            assertEquals(before.getFirst(), jdbc.queryForList("SELECT * FROM workout_record ORDER BY id").getFirst());
        }
    }

    @ParameterizedTest
    @CsvSource({"negative_food,400,Invalid agent request", "invalid_date,400,Invalid agent request",
            "missing_food,400,Invalid agent request", "missing_plan,400,Invalid agent request",
            "auth_failure,401,This API key is invalid or unavailable",
            "provider_failure,500,Internal server error. Please try again later",
            "file_failure,500,Failed to read file: Agent file read failed"})
    void failuresKeepHttpClassificationAndDoNotSaveHistory(String scenario, int status, String message) throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            JsonNode error = app.chat(scenario, status);
            assertEquals(message, error.path("error").asText());
            assertEquals(1, error.size());
            assertFalse(error.toString().contains("SYNTHETIC"));
            assertTrue(app.get(HISTORY).isEmpty());
            assertTrue(app.get(FOOD_DATE).isEmpty());
            assertEquals(0, count(jdbc(app)));
            // The same worker can serve a clean request after an ordinary terminal failure.
            assertEquals(0, reply(app.chat("reply", 200)).path("historySize").asInt());
            assertEquals(2, app.get(HISTORY).size());
        }
    }

    @Test
    void missingKeyAndProviderFailureAfterWriteNeverReplayOrAppendFailedHistory() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            var keys = app.context.getBean(IApiKeyService.class);
            keys.save("");
            app.chat("food", 400);
            assertEquals(0, app.gate.foodCalls.get());
            keys.save("SYNTHETIC_NEW_KEY");
            app.chat("food_then_failure", 500);
            assertEquals(1, app.get(FOOD_DATE).size());
            assertEquals(1, app.gate.foodCalls.get());
            assertTrue(app.get(HISTORY).isEmpty());
            app.chat("reply", 200);
            assertEquals(1, app.get(FOOD_DATE).size());
            assertEquals(2, app.get(HISTORY).size());
        }
    }

    @Test
    void historyWriteFailureKeepsCommittedFoodAndHistoryReadFailureKeepsLegacyRecovery() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            Path history = workspace.resolve("chat-history.json");
            Files.createDirectory(history); // Real filesystem failure, not a mocked append exception.
            app.chat("food", 500);
            assertEquals(1, app.gate.foodCalls.get());
            assertEquals(1, app.get(FOOD_DATE).size());
            Files.delete(history);
            Files.writeString(history, "invalid history JSON");
            // The existing history service deliberately recovers unreadable JSON as empty history.
            assertEquals(0, reply(app.chat("reply", 200)).path("historySize").asInt());
            assertEquals(2, app.get(HISTORY).size());
            assertEquals(1, app.gate.foodCalls.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"before_write,time.current,0", "two_food,food.insert,1"})
    void workerDeathBeforeWriteOrAfterCommitDoesNotReplay(String scenario, String barrier, int records) throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            app.gate.pauseAfter(barrier);
            var response = app.chatAsync(scenario);
            assertTrue(app.gate.entered.await(10, TimeUnit.SECONDS));
            assertEquals(records, app.get(FOOD_DATE).size());
            long pid = app.worker.pid();
            ProcessHandle.of(pid).orElseThrow().destroyForcibly();
            body(response.get(10, TimeUnit.SECONDS), 500);
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            app.gate.release.countDown();
            assertTrue(app.gate.returned.await(5, TimeUnit.SECONDS));
            restartAfterSettlement(app);
            assertEquals(0, reply(app.chat("reply", 200)).path("historySize").asInt());
            assertEquals(records, app.get(FOOD_DATE).size());
            assertEquals(records, app.gate.foodCalls.get());
            assertEquals(2, app.get(HISTORY).size());
        }
    }

    @Test
    void concurrentHttpRequestsUseOrderedHistoryWithoutCrossRequestResults() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            app.gate.pauseAfter("food.insert");
            var first = app.chatAsync("food");
            assertTrue(app.gate.entered.await(10, TimeUnit.SECONDS));
            var second = app.chatAsync("reply");
            var lane = app.context.getBean(ConversationLane.class);
            await(() -> lane.queuedRequests() == 1);
            assertFalse(second.isDone());
            app.gate.release.countDown();
            assertEquals(0, reply(body(first.get(10, TimeUnit.SECONDS), 200)).path("historySize").asInt());
            assertEquals(2, reply(body(second.get(10, TimeUnit.SECONDS), 200)).path("historySize").asInt());
            JsonNode history = app.get(HISTORY);
            assertEquals(4, history.size());
            assertEquals("food", history.get(0).path("content").asText());
            assertEquals("reply", history.get(2).path("content").asText());
            assertEquals(1, app.gate.foodCalls.get());
        }
    }

    @Test
    void deadlineCancelsRemainingToolsAndLateCommitResultCannotContaminateNextRun() throws Exception {
        try (var app = new PersistenceTestApplication(workspace, "5s")) {
            app.gate.pauseAfter("food.insert");
            var response = app.chatAsync("two_food");
            assertTrue(app.gate.entered.await(10, TimeUnit.SECONDS));
            long pid = app.worker.pid();
            body(response.get(10, TimeUnit.SECONDS), 500);
            assertEquals(1, app.get(FOOD_DATE).size());
            assertTrue(app.get(HISTORY).isEmpty());
            // A new HTTP request is rejected while the old operation has not settled.
            app.chat("food", 500);
            assertEquals(1, app.gate.foodCalls.get());
            app.gate.release.countDown();
            assertTrue(app.gate.returned.await(5, TimeUnit.SECONDS));
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            restartAfterSettlement(app);
            assertEquals(0, reply(app.chat("reply", 200)).path("historySize").asInt());
            assertEquals(2, app.get(HISTORY).size());
            assertEquals(1, app.gate.foodCalls.get());
        }
    }

    @Test
    void shutdownDuringCommittedOperationStopsWorkerAndRemainingTools() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            app.gate.pauseAfter("food.insert");
            var response = app.chatAsync("two_food");
            assertTrue(app.gate.entered.await(10, TimeUnit.SECONDS));
            long pid = app.worker.pid();
            app.worker.close();
            body(response.get(10, TimeUnit.SECONDS), 500);
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            app.gate.release.countDown();
            assertTrue(app.gate.returned.await(5, TimeUnit.SECONDS));
            assertEquals(1, app.get(FOOD_DATE).size());
            assertTrue(app.get(HISTORY).isEmpty());
            assertEquals(1, app.gate.foodCalls.get());
        }
    }

    @Test
    void settingsSavedOverHttpChooseTheProviderModelAndKeyForTheRealWorker() throws Exception {
        try (var app = new PersistenceTestApplication(workspace)) {
            JsonNode openai = reply(app.chat("reply", 200));
            assertEquals("openai", openai.path("provider").asText());
            assertEquals("gpt-6-sol", openai.path("model").asText());

            app.http("PUT", "/api/llm/settings", """
                    {"activeProvider":"anthropic","models":{"openai":"gpt-6-sol",
                     "anthropic":"claude-haiku-5-5","deepseek":"deepseek-flash"}}""", 200);
            // The saved OpenAI key never substitutes for the missing Claude key.
            assertEquals("Claude API key is empty; chat is unavailable",
                    app.chat("reply", 400).path("error").asText());
            assertEquals(2, app.get(HISTORY).size());

            app.http("PUT", "/api/llm/keys/anthropic", "{\"apiKey\":\"SYNTHETIC_CLAUDE_KEY\"}", 200);
            JsonNode claude = reply(app.chat("reply", 200));
            assertEquals("anthropic", claude.path("provider").asText());
            assertEquals("claude-haiku-5-5", claude.path("model").asText());
            assertEquals(2, claude.path("historySize").asInt());
            assertEquals(4, app.get(HISTORY).size());
        }
    }

    private static JsonNode reply(JsonNode response) throws Exception {
        assertEquals(1, response.size());
        return JSON.readTree(response.path("reply").asText());
    }

    private static JdbcTemplate jdbc(PersistenceTestApplication app) {
        return app.context.getBean("workoutJdbcTemplate", JdbcTemplate.class);
    }

    private static int count(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workout_record", Integer.class);
    }

    private static void restartAfterSettlement(PersistenceTestApplication app) throws Exception {
        await(() -> {
            try { app.worker.start(); return true; }
            catch (org.tyler.service.agent.protocol.ProtocolException pending) { return false; }
        });
    }
}
