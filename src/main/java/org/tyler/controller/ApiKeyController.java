package org.tyler.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.service.IApiKeyService;

/**
 * OpenAI API Key 相关的 REST 控制器（薄控制器）。
 *
 * <p>只负责 HTTP 层：路由映射 + 请求体绑定 + 委托给 {@link IApiKeyService}。
 * 出于安全考虑，任何接口都不回传 key 的明文值，只返回「是否已配置」的布尔状态。
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