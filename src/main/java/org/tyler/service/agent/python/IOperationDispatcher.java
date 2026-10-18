package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;

/** Step 6 supplies allowlisted service handlers. Called off the pipe-reader and conversation threads. */
@FunctionalInterface
public interface IOperationDispatcher {
    JsonNode dispatch(String operation, JsonNode input);
}
