package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.service.agent.bridge.handler.FoodInsertAllHandler;
import org.tyler.service.food.IFoodService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

class FoodInsertAllHandlerTest {
    private final IFoodService service = mock(IFoodService.class);
    private final OperationPayloadMapper mapper = new OperationPayloadMapper();
    private final FoodInsertAllHandler handler = new FoodInsertAllHandler(service, mapper);

    @Test
    void savesTheWholeListInOneServiceCallAndReturnsEachIdBesideItsFood() {
        ObjectNode request = input("food");
        ArrayNode foods = (ArrayNode) request.get("foods");
        ObjectNode banana = foods.get(0).deepCopy();
        ((ObjectNode) banana.get("genericInfo")).put("foodName", "Banana");
        foods.add(banana);
        List<Food> list = List.of(mapper.read(foods.get(0), Food.class), mapper.read(banana, Food.class));
        when(service.insertFoods(list)).thenReturn(
                List.of(new FoodEntry(Long.MAX_VALUE, list.get(0)), new FoodEntry(7, list.get(1))));

        JsonNode saved = handler.execute(request).get("foods");

        assertEquals(2, saved.size());
        assertEquals(Long.toString(Long.MAX_VALUE), saved.get(0).get("id").textValue());
        assertEquals(foods.get(0), saved.get(0).get("food"));
        assertEquals("7", saved.get(1).get("id").textValue());
        assertEquals(banana, saved.get(1).get("food"));
        // One call carries both foods, so the service can save them in one transaction.
        verify(service).insertFoods(list);
        verifyNoMoreInteractions(service);
    }

    @Test
    void aServiceFailureKeepsItsIdentity() {
        var original = new IllegalArgumentException("invalid food");
        when(service.insertFoods(any())).thenThrow(original);
        assertSame(original, assertThrowsExactly(IllegalArgumentException.class, () -> handler.execute(input("food"))));
    }
}
