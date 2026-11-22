package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.bridge.OperationPayloadMapper;
import org.tyler.service.food.IFoodService;

import java.util.ArrayList;
import java.util.List;

/**
 * Saves the foods of one {@code recordFood} call through the shared food service, which saves
 * all of them or none. Each saved food keeps its generated ID and decimal values, in the
 * input's order.
 */
@Component
public final class FoodInsertAllHandler implements IOperationHandler {
    private final IFoodService service;
    private final OperationPayloadMapper mapper;

    public FoodInsertAllHandler(IFoodService service, OperationPayloadMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String operationName() {
        return "food.insert_all";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        List<Food> foods = new ArrayList<>();
        for (JsonNode food : input.get("foods")) {
            foods.add(mapper.read(food, Food.class));
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode saved = result.putArray("foods");
        for (FoodEntry entry : service.insertFoods(foods)) {
            saved.add(mapper.value("food", entry.food()).put("id", Long.toString(entry.id())));
        }
        return result;
    }
}
