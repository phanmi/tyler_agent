package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.bridge.OperationPayloadMapper;
import org.tyler.service.workout.IWorkoutPlanService;

/** Delegates plan validation, generation, duplicate policy, and atomic saving to the plan service. */
@Component
public final class WorkoutPlanSaveHandler implements IOperationHandler {
    private final IWorkoutPlanService service;
    private final OperationPayloadMapper mapper;

    public WorkoutPlanSaveHandler(IWorkoutPlanService service, OperationPayloadMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String operationName() {
        return "workout_plan.generate_save";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        return mapper.value("plan", service.generateAndSave(input.get("startDate").textValue(),
                input.get("goal").textValue(), input.get("equipment").textValue()));
    }
}
