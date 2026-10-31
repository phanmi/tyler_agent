package org.tyler.controller.llm;

import java.util.List;
import java.util.Map;

/**
 * REST contract for model provider settings and per-provider API keys.
 *
 * <p>Responses report only whether each key is configured, never a key value.
 */
public interface ILlmController {

    /** Returns the active provider and, per provider, its model, suggestions, and key status. */
    SettingsResponse settings();

    /** Replaces the active provider and every provider's model, then returns the new settings. */
    SettingsResponse saveSettings(SettingsRequest request);

    /** Saves or, when blank, clears one provider's key and returns its configuration status. */
    KeyStatusResponse saveKey(String provider, KeyRequest request);

    /** All three provider models are required; unknown provider names are rejected. */
    record SettingsRequest(String activeProvider, Map<String, String> models) {}

    record SettingsResponse(String activeProvider, List<ProviderSettings> providers) {}

    /** {@code name} is the display name, e.g. "Claude" for {@code anthropic}. */
    record ProviderSettings(String id, String name, String model, String defaultModel,
                            List<String> suggestedModels, boolean configured) {}

    record KeyRequest(String apiKey) {
        /** Spring's DEBUG request logging prints bound bodies; never include the key. */
        @Override
        public String toString() {
            return "KeyRequest[apiKey=<redacted>]";
        }
    }

    record KeyStatusResponse(String provider, boolean configured) {}
}
