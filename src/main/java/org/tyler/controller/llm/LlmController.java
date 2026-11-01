package org.tyler.controller.llm;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.model.llm.LlmProvider;
import org.tyler.model.llm.LlmSettings;
import org.tyler.service.llm.ILlmKeyService;
import org.tyler.service.llm.ILlmSettingsService;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for model provider settings and per-provider API keys.
 *
 * <p>Binds requests, converts provider names, and delegates to {@link ILlmSettingsService}
 * and {@link ILlmKeyService}. Invalid names, model IDs, and keys become HTTP 400 through
 * {@link IllegalArgumentException}, with fixed messages that never echo the input.
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController implements ILlmController {

    private final ILlmSettingsService settingsService;
    private final ILlmKeyService keyService;

    public LlmController(ILlmSettingsService settingsService, ILlmKeyService keyService) {
        this.settingsService = settingsService;
        this.keyService = keyService;
    }

    @Override
    @GetMapping("/settings")
    public SettingsResponse settings() {
        return response(settingsService.get());
    }

    @Override
    @PutMapping("/settings")
    public SettingsResponse saveSettings(@RequestBody SettingsRequest request) {
        return response(settingsService.save(toSettings(request)));
    }

    @Override
    @DeleteMapping("/settings")
    public SettingsResponse resetSettings() {
        return response(settingsService.save(LlmSettings.defaults()));
    }

    @Override
    @PutMapping("/keys/{provider}")
    public KeyStatusResponse saveKey(@PathVariable String provider, @RequestBody KeyRequest request) {
        LlmProvider target = LlmProvider.fromId(provider);
        keyService.save(target, request.apiKey());
        return new KeyStatusResponse(target.id(), keyService.isConfigured(target));
    }

    /** Validates the whole request before anything is saved. */
    private static LlmSettings toSettings(SettingsRequest request) {
        if (request.models() == null) {
            throw new IllegalArgumentException("Missing field: models");
        }
        Map<LlmProvider, String> models = new EnumMap<>(LlmProvider.class);
        for (Map.Entry<String, String> entry : request.models().entrySet()) {
            models.put(LlmProvider.fromId(entry.getKey()), entry.getValue());
        }
        if (request.activeProvider() == null) {
            throw new IllegalArgumentException("Missing field: activeProvider");
        }
        return new LlmSettings(LlmProvider.fromId(request.activeProvider()), models);
    }

    private SettingsResponse response(LlmSettings settings) {
        List<ProviderSettings> providers = Arrays.stream(LlmProvider.values())
                .map(provider -> new ProviderSettings(provider.id(), provider.displayName(), settings.model(provider),
                        provider.defaultModel(), provider.suggestedModels(), keyService.isConfigured(provider)))
                .toList();
        return new SettingsResponse(settings.activeProvider().id(), providers);
    }
}
