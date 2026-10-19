package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;

/** Allowlisted service operations, called off the pipe-reader and conversation threads. */
@FunctionalInterface
public interface IOperationDispatcher {
    JsonNode dispatch(String operation, JsonNode input);
}
