package org.tyler.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.tyler.tool.foodRecordTool.RecordFoodTool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordFoodToolTest {

    private final RecordFoodTool tool = new RecordFoodTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesValidFood() throws Exception {
        String args = """
                {
                  "genericInfo": {
                    "foodName": "Chicken breast",
                    "amount": 200,
                    "unit": "g",
                    "calories": 330,
                    "date": "2026-09-08"
                  },
                  "macroNutrients": {
                    "protein": 62,
                    "carbs": 0,
                    "fat": 7,
                    "fiber": 0
                  }
                }
                """;
        JsonNode node = mapper.readTree(tool.execute(args));
        JsonNode info = node.get("genericInfo");
        JsonNode macros = node.get("macroNutrients");
        assertEquals("Chicken breast", info.get("foodName").asText());
        assertEquals("200", info.get("amount").asText());
        assertEquals("2026-09-08", info.get("date").asText());
        assertEquals("62", macros.get("protein").asText());
    }

    @Test
    void throwsWhenGenericInfoMissing() {
        String args = """
                {"macroNutrients":{"protein":62,"carbs":0,"fat":7,"fiber":0}}
                """;
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
    }

    @Test
    void throwsWhenFoodNameMissing() {
        String args = """
                {
                  "genericInfo": {"amount":200,"unit":"g","calories":330,"date":"2026-09-08"},
                  "macroNutrients": {"protein":62,"carbs":0,"fat":7,"fiber":0}
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
    }

    @Test
    void throwsWhenJsonInvalid() {
        assertThrows(IllegalArgumentException.class, () -> tool.execute("not-json"));
    }

    @Test
    void throwsWhenNegativeValue() {
        String args = """
                {
                  "genericInfo": {"foodName":"x","amount":-1,"unit":"g","calories":1,"date":"2026-09-08"},
                  "macroNutrients": {"protein":1,"carbs":1,"fat":1,"fiber":1}
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> tool.execute(args));
    }

    @Test
    void exposesNameAndSchema() {
        assertEquals("recordFood", tool.name());
        assertNotNull(tool.toFunctionTool());
    }
}
