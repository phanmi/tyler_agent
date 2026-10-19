package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.tyler.service.agent.bridge.handler.FoodInsertHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

class FoodInsertHandlerTest {
    @Test
    void insertsWithStableIdAndRetainsDomainFailure() {
        var service = mock(org.tyler.service.food.IFoodService.class);
        var mapper = new OperationPayloadMapper();
        var handler = new FoodInsertHandler(service, mapper);
        var request = input("food");
        var food = mapper.read(request.get("food"), org.tyler.model.food.Food.class);
        when(service.insertFood(food)).thenReturn(new org.tyler.model.food.FoodEntry(Long.MAX_VALUE, food));
        var result = handler.execute(request);
        assertEquals(Long.toString(Long.MAX_VALUE), result.get("id").textValue());
        assertEquals(request.get("food"), result.get("food"));
        var original = new IllegalArgumentException("invalid food");
        when(service.insertFood(food)).thenThrow(original);
        assertSame(original, assertThrowsExactly(IllegalArgumentException.class, () -> handler.execute(request)));
    }
}
