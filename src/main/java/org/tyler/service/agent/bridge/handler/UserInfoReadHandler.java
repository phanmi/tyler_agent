package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.bridge.OperationPayloadMapper;
import org.tyler.service.userInfo.IUserInfoService;

/** Reads through the profile service, retaining capitalized fields and nullable components. */
@Component
@ConditionalOnProperty(name = "agent.implementation", havingValue = "python")
public final class UserInfoReadHandler implements IOperationHandler {
    private final IUserInfoService service;
    private final OperationPayloadMapper mapper;

    public UserInfoReadHandler(IUserInfoService service, OperationPayloadMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String operationName() {
        return "user_info.read";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        var profile = service.get();
        if (profile == null) {
            // Preserve the legacy failure instead of inventing a successful empty profile.
            throw new IllegalStateException("Stored agent profile is null");
        }
        return mapper.value("profile", profile);
    }
}
