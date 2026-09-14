package org.tyler.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tyler.dal.IFoodRecordDAL;
import org.tyler.tool.foodRecordTool.RecordFoodTool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordFoodToolTest {

    @Mock
    private IFoodRecordDAL dal;

    private RecordFoodTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new RecordFoodTool(dal);
    }

    private static final String VALID_ARGS = """
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

    @Test
    void parsesValidFood() throws Exception {
        when(dal.saveFoodByDate(any())).thenAnswer(inv -> inv.getArgument(0));
        JsonNode node = mapper.readTree(tool.execute(VALID_ARGS));
        JsonNode info = node.get("genericInfo");
        JsonNode macros = node.get("macroNutrients");
        assertEquals("Chicken breast", info.get("foodName").asText());
        assertEquals("200", info.get("amount").asText());
        assertEquals("2026-09-08", info.get("date").asText());
        assertEquals("62", macros.get("protein").asText());
    }

    @Test
    void savesFoodOnceAndReturnsFoodJson() throws Exception {
        when(dal.saveFoodByDate(any())).thenAnswer(inv -> inv.getArgument(0));
        String result = tool.execute(VALID_ARGS);
        verify(dal, times(1)).saveFoodByDate(any());
        JsonNode node = mapper.readTree(result);
        assertNotNull(node.get("genericInfo"));
        assertNotNull(node.get("macroNutrients"));
    }

    @Test
    void propagatesDalException() {
        when(dal.saveFoodByDate(any())).thenThrow(new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, () -> tool.execute(VALID_ARGS));
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
