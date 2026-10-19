package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.JsonNode;

/** One private protocol operation; domain rules and transactions remain in its shared service. */
public interface IOperationHandler {
    /** Stable, case-sensitive name from the versioned protocol allowlist. */
    String operationName();

    /**
     * Convert already protocol-validated input, invoke the operation, and return its transport result.
     * Service/sandbox exceptions propagate unchanged. Call through IOperationDispatcher at runtime.
     */
    JsonNode execute(JsonNode input);
}
