package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.tyler.model.food.Food;
import org.tyler.model.userInfo.UserInfo;
import org.tyler.model.workout.WorkoutPlan;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.tyler.service.agent.python.WorkerTestSupport.fixture;

/** Frozen release contracts protect Python tool schemas and Java-owned output values. */
class PythonToolContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pythonDefinitionsMatchReleasedSchemasAndDescriptions() throws Exception {
        List<JsonNode> definitions = new ArrayList<>();
        try (var paths = Files.list(Path.of("agent-python/src/tyler_agent/tools/builtin"))) {
            for (Path folder : paths.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("_")).sorted().toList()) {
                definitions.add(MAPPER.readTree(folder.resolve("definition.json").toFile()));
            }
        }
        JsonNode expected = MAPPER.readTree(Path.of("contracts/agent/v1/fixtures/tool-definitions.json").toFile());
        assertEquals(expected, MAPPER.valueToTree(definitions));
    }

    @Test
    void sharedOutputGoldensPreserveReleasedFormatting() throws Exception {
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
