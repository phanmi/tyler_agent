package org.tyler.controller.apiKey;

/**
 * REST contract for OpenAI API key settings.
 */
public interface IApiKeyController {

    /** Returns whether a key is configured, without exposing its value. */
    StatusResponse status();

    /** Saves the key and returns the resulting configuration status. */
    StatusResponse save(SaveRequest request);

    record StatusResponse(boolean configured) {}

    record SaveRequest(String apiKey) {
        /** Spring's DEBUG request logging prints bound bodies; never include the key. */
        @Override
        public String toString() {
            return "SaveRequest[apiKey=<redacted>]";
        }
    }
}