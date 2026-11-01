package org.tyler.service.apiKey;

import org.springframework.stereotype.Service;
import org.tyler.model.llm.LlmProvider;
import org.tyler.service.llm.ILlmKeyService;

/**
 * Service for reading and saving the OpenAI API key.
 *
 * <p>Kept for {@code /api/apikey} and existing callers. The key file has one owner,
 * {@link ILlmKeyService}, which applies the same storage and validation to every provider.
 */
@Service
public class ApiKeyService implements IApiKeyService {

    private final ILlmKeyService keys;

    public ApiKeyService(ILlmKeyService keys) {
        this.keys = keys;
    }

    @Override
    public boolean isConfigured() {
        return keys.isConfigured(LlmProvider.OPENAI);
    }

    @Override
    public String get() {
        return keys.get(LlmProvider.OPENAI);
    }

    @Override
    public void save(String apiKey) {
        keys.save(LlmProvider.OPENAI, apiKey);
    }
}
