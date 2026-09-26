package org.tyler.controller.apiKey;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.service.apiKey.IApiKeyService;

/**
 * REST controller for OpenAI API key settings.
 *
 * <p>Maps routes, binds request bodies, and delegates to {@link IApiKeyService}.
 * Responses expose only configuration status, never the API key.
 */
@RestController
@RequestMapping("/api/apikey")
public class ApiKeyController implements IApiKeyController {

    private final IApiKeyService apiKeyService;

    public ApiKeyController(IApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(apiKeyService.isConfigured());
    }

    @Override
    @PostMapping
    public StatusResponse save(@RequestBody SaveRequest request) {
        apiKeyService.save(request.apiKey());
        return new StatusResponse(apiKeyService.isConfigured());
    }
}