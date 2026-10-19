package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodEntry;
import org.tyler.service.agent.protocol.MessageValidator;
import org.tyler.service.agent.protocol.ProtocolException;
import org.tyler.service.agent.python.IOperationDispatcher;
import org.tyler.service.food.IFoodService;
import org.tyler.service.userInfo.IUserInfoService;
import org.tyler.service.workout.IWorkoutPlanService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Allowlisted service access for the private worker protocol. No model arguments, provider
 * objects, or tool execution enter this boundary. Domain failures propagate with their causes.
 */
public final class ServiceOperationDispatcher implements IOperationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ServiceOperationDispatcher.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private final IFoodService foodService;
    private final IWorkoutPlanService planService;
    private final IUserInfoService userInfoService;
    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final Clock clock;
    private final MessageValidator validator = new MessageValidator();
    private final ObjectMapper mapper = transportMapper();

    public ServiceOperationDispatcher(IFoodService foodService, IWorkoutPlanService planService,
                                      IUserInfoService userInfoService, IFileSandboxRead reader,
                                      IFileSandboxWrite writer, Clock clock) {
        this.foodService = foodService;
        this.planService = planService;
        this.userInfoService = userInfoService;
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    /** Reject invalid transport before side effects; service/sandbox validation remains authoritative. */
    @Override
    public JsonNode dispatch(String operation, JsonNode input) {
        validator.validateOperationInput(operation, input);
        JsonNode result = switch (operation) {
            case "food.insert" -> insertFood(input);
            case "workout_plan.generate_save" -> savePlan(input);
            case "user_info.read" -> profile();
            case "file.read" -> mapper.createObjectNode().put("content", reader.read(input.get("path").textValue()));
            case "file.write" -> writeFile(input);
            case "time.current" -> mapper.createObjectNode().put("text", TIME_FORMAT.format(ZonedDateTime.now(clock)));
            default -> throw new ProtocolException("UNKNOWN_OPERATION");
        };
        // The owning supervisor retains/logs failures. Never log payloads, paths, or profile data here.
        log.debug("Agent service operation completed: operation={}", operation);
        return result;
    }

    private JsonNode insertFood(JsonNode input) {
        Food food;
        try {
            food = mapper.treeToValue(input.get("food"), Food.class);
        } catch (JsonProcessingException e) {
            // A shape-valid wire object must be convertible; this is not a domain rejection.
            throw new IllegalStateException("Cannot decode agent food operation", e);
        }
        FoodEntry saved = foodService.insertFood(food);
        return value("food", saved.food()).put("id", Long.toString(saved.id()));
    }

    private JsonNode savePlan(JsonNode input) {
        return value("plan", planService.generateAndSave(input.get("startDate").textValue(),
                input.get("goal").textValue(), input.get("equipment").textValue()));
    }

    private JsonNode profile() {
        var profile = userInfoService.get();
        if (profile == null) {
            // The legacy tool fails on a null top-level profile; do not fabricate a successful read.
            throw new IllegalStateException("Stored agent profile is null");
        }
        return value("profile", profile);
    }

    private JsonNode writeFile(JsonNode input) {
        String path = input.get("path").textValue();
        writer.write(path, input.get("content").textValue());
        return mapper.createObjectNode().put("path", path);
    }

    private ObjectNode value(String field, Object value) {
        try {
            return mapper.createObjectNode().set(field, mapper.valueToTree(value));
        } catch (IllegalArgumentException e) {
            // Serialization can follow a committed write. Never relabel it as invalid user input.
            throw new IllegalStateException("Cannot encode agent operation result", e);
        }
    }

    private static ObjectMapper transportMapper() {
        SimpleModule decimals = new SimpleModule();
        decimals.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator output, SerializerProvider provider) throws IOException {
                output.writeString(value.toPlainString());
            }
        });
        return new ObjectMapper().registerModule(decimals);
    }
}
