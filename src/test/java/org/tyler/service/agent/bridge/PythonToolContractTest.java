package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import org.junit.jupiter.api.Test;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.food.Food;
import org.tyler.model.userInfo.UserInfo;
import org.tyler.model.workout.WorkoutPlan;
import org.tyler.service.food.IFoodService;
import org.tyler.service.userInfo.IUserInfoService;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.tool.ITool;
import org.tyler.tool.fileTool.ReadFileTool;
import org.tyler.tool.fileTool.WriteFileTool;
import org.tyler.tool.foodRecordTool.RecordFoodTool;
import org.tyler.tool.timeManagementTool.GetCurrentTimeTool;
import org.tyler.tool.userTool.GetUserInfoTool;
import org.tyler.tool.workoutPlanTool.GenerateWorkoutPlanTool;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.tyler.service.agent.python.WorkerTestSupport.fixture;

/** Migration parity guard: Python model contracts must match the characterized Java adapters. */
class PythonToolContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void allSixPythonDefinitionsMatchCompleteJavaSchemasAndDescriptions() throws Exception {
        JsonNode definitions = MAPPER.readTree(Path.of(
                "agent-python/src/tyler_agent/tools/definitions.json").toFile());
        List<ITool> tools = List.of(new RecordFoodTool(mock(IFoodService.class)),
                new GenerateWorkoutPlanTool(mock(IWorkoutPlanService.class)),
                new GetUserInfoTool(mock(IUserInfoService.class)),
                new ReadFileTool(mock(IFileSandboxRead.class)),
                new WriteFileTool(mock(IFileSandboxWrite.class)), new GetCurrentTimeTool());
        assertEquals(tools.size(), definitions.size());
        for (ITool tool : tools) {
            JsonNode expected = ObjectMappers.jsonMapper().valueToTree(tool.toFunctionTool());
            JsonNode actual = definitions.findParents("name").stream()
                    .filter(value -> value.path("name").asText().equals(tool.name())).findFirst().orElseThrow();
            assertEquals(expected, actual, tool.name());
        }
    }

    @Test
    void sharedOutputGoldensMatchLegacyFormatting() throws Exception {
        JsonNode expected = MAPPER.readTree(Path.of("contracts/agent/v1/fixtures/tool-output.json").toFile());
        assertEquals(expected.get("food").asText(), normalize(MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(MAPPER.treeToValue(result("food", "food"), Food.class))));
        assertEquals(expected.get("plan").asText(), normalize(MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(MAPPER.treeToValue(result("plan", "plan"), WorkoutPlan.class))));
        assertEquals(expected.get("profile").asText(),
                MAPPER.treeToValue(result("profile", "profile"), UserInfo.class).toString());
    }

    private static JsonNode result(String prefix, String field) throws Exception {
        return fixture(prefix + "_result").path("payload").path("result").path(field);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
