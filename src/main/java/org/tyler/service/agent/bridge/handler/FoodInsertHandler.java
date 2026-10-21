package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.tyler.model.food.Food;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.bridge.OperationPayloadMapper;
import org.tyler.service.food.IFoodService;

/** Appends through the shared food service and preserves its generated ID and decimal values. */
@Component
public final class FoodInsertHandler implements IOperationHandler {
    private final IFoodService service;
    private final OperationPayloadMapper mapper;

    public FoodInsertHandler(IFoodService service, OperationPayloadMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String operationName() {
        return "food.insert";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        var saved = service.insertFood(mapper.read(input.get("food"), Food.class));
        return mapper.value("food", saved.food()).put("id", Long.toString(saved.id()));
    }
}
