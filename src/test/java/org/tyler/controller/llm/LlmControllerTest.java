package org.tyler.controller.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.controller.apiKey.ApiKeyController;
import org.tyler.exceptionHandler.FileExceptionHandler;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.llm.LlmProvider;
import org.tyler.model.llm.LlmSettings;
import org.tyler.service.apiKey.ApiKeyService;
import org.tyler.service.llm.ILlmKeyService;
import org.tyler.service.llm.ILlmSettingsService;
import org.tyler.service.llm.LlmKeyService;
import org.tyler.service.llm.LlmSettingsService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP tests for {@link LlmController}: response shape, validation before any save, and
 * keys that never appear in responses or logs. The OpenAI {@code /api/apikey} endpoint is
 * covered alongside, since it shares the key service.
 */
@ExtendWith(OutputCaptureExtension.class)
class LlmControllerTest {

    private static final String KEY = "SYNTHETIC_FIXTURE_KEY_NEVER_SEND_TO_PROVIDER";
    private static final String VALID_SETTINGS = """
            {"activeProvider":"anthropic","models":{"openai":"gpt-5.6","anthropic":"claude-opus-5-5","deepseek":"deepseek-flash"}}
            """;

    private ILlmSettingsService settings;
    private ILlmKeyService keys;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        settings = mock(ILlmSettingsService.class);
        keys = mock(ILlmKeyService.class);
        mockMvc = mvc(settings, keys);
    }

    private static MockMvc mvc(ILlmSettingsService settings, ILlmKeyService keys) {
        return MockMvcBuilders.standaloneSetup(new LlmController(settings, keys),
                        new ApiKeyController(new ApiKeyService(keys)))
                .setControllerAdvice(new FileExceptionHandler(), new GenericExceptionHandler())
                .build();
    }

    @Test
    void settingsReportModelsSuggestionsAndKeyStatusPerProvider() throws Exception {
        when(settings.get()).thenReturn(LlmSettings.defaults());
        when(keys.isConfigured(LlmProvider.ANTHROPIC)).thenReturn(true);

        mockMvc.perform(get("/api/llm/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProvider").value("openai"))
                .andExpect(jsonPath("$.providers.length()").value(3))
                .andExpect(jsonPath("$.providers[0].id").value("openai"))
                .andExpect(jsonPath("$.providers[0].model").value("gpt-6-sol"))
                .andExpect(jsonPath("$.providers[0].configured").value(false))
                .andExpect(jsonPath("$.providers[1].id").value("anthropic"))
                .andExpect(jsonPath("$.providers[1].name").value("Claude"))
                .andExpect(jsonPath("$.providers[1].defaultModel").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.providers[1].suggestedModels[1]").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.providers[1].configured").value(true))
                .andExpect(jsonPath("$.providers[2].model").value("deepseek-flash"));
        verify(keys, never()).get(any());
    }

    @Test
    void savingSettingsReplacesThemAndReturnsTheResult() throws Exception {
        when(settings.save(any())).thenAnswer(call -> call.getArgument(0));

        mockMvc.perform(put("/api/llm/settings").contentType(MediaType.APPLICATION_JSON).content(VALID_SETTINGS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProvider").value("anthropic"))
                .andExpect(jsonPath("$.providers[1].model").value("claude-opus-5-5"));
        verify(settings).save(new LlmSettings(LlmProvider.ANTHROPIC, Map.of(
                LlmProvider.OPENAI, "gpt-5.6",
                LlmProvider.ANTHROPIC, "claude-opus-5-5",
                LlmProvider.DEEPSEEK, "deepseek-flash")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"activeProvider\":\"mistral\",\"models\":{\"openai\":\"a\",\"anthropic\":\"b\",\"deepseek\":\"c\"}}",
            "{\"models\":{\"openai\":\"a\",\"anthropic\":\"b\",\"deepseek\":\"c\"}}",
            "{\"activeProvider\":\"openai\"}",
            "{\"activeProvider\":\"openai\",\"models\":{\"openai\":\"a\",\"anthropic\":\"b\"}}",
            "{\"activeProvider\":\"openai\",\"models\":{\"openai\":\"a\",\"anthropic\":\"b\",\"deepseek\":\"c\",\"x\":\"d\"}}",
            "{\"activeProvider\":\"openai\",\"models\":{\"openai\":\"MARKER<script>\",\"anthropic\":\"b\",\"deepseek\":\"c\"}}",
            "{\"activeProvider\":\"openai\",\"models\":{\"openai\":\"\",\"anthropic\":\"b\",\"deepseek\":\"c\"}}",
            "not json"})
    void invalidSettingsReturn400WithoutSavingOrEchoing(String body) throws Exception {
        mockMvc.perform(put("/api/llm/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("MARKER"))))
                .andExpect(content().string(not(containsString("mistral"))));
        verify(settings, never()).save(any());
    }

    @Test
    void anInvalidSettingsFileIsAServerErrorWithoutItsContent() throws Exception {
        when(settings.get()).thenThrow(new FileReadException("LLM settings file is invalid",
                new IllegalArgumentException("Unknown provider")));

        mockMvc.perform(get("/api/llm/settings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Failed to read file: LLM settings file is invalid"));
    }

    @Test
    void savingAKeyReturnsOnlyItsStatus() throws Exception {
        when(keys.isConfigured(LlmProvider.DEEPSEEK)).thenReturn(true);

        mockMvc.perform(put("/api/llm/keys/deepseek").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("deepseek"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(content().string(not(containsString(KEY))));
        verify(keys).save(LlmProvider.DEEPSEEK, KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mistral", "OpenAI", "MARKER%3Cscript%3E"})
    void unknownKeyProvidersReturn400BeforeAnySave(String provider) throws Exception {
        mockMvc.perform(put("/api/llm/keys/" + provider).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown provider"));
        verifyNoInteractions(keys);
    }

    @Test
    void realServicesNeverReturnKeysAndRejectBadKeysOnEveryEndpoint(@TempDir Path tempDir, CapturedOutput output)
            throws Exception {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        MockMvc real = mvc(new LlmSettingsService(sandbox, sandbox, "llm-settings.json"),
                new LlmKeyService(sandbox, sandbox, "apikey.txt", "anthropic-key.txt", "deepseek-key.txt", "llm-settings.json"));

        real.perform(put("/api/llm/keys/anthropic").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isOk());
        real.perform(post("/api/apikey").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + KEY + "-openai\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
        real.perform(get("/api/llm/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].configured").value(true))
                .andExpect(jsonPath("$.providers[1].configured").value(true))
                .andExpect(jsonPath("$.providers[2].configured").value(false))
                .andExpect(content().string(not(containsString(KEY))));

        for (var request : new org.springframework.test.web.servlet.RequestBuilder[]{
                put("/api/llm/keys/deepseek").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"REJECTED_MARKER with space\"}"),
                post("/api/apikey").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"REJECTED_MARKER\\nnewline\"}")}) {
            real.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(LlmKeyService.INVALID_KEY_MESSAGE));
        }
        assertEquals(KEY + "-openai", Files.readString(tempDir.resolve("apikey.txt")));
        assertFalse(Files.exists(tempDir.resolve("deepseek-key.txt")));
        assertFalse(output.getAll().contains(KEY));
        assertFalse(output.getAll().contains("REJECTED_MARKER"));
    }

    @Test
    void malformedKeyBodiesAreNotLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(put("/api/llm/keys/openai").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\": SYNTHETIC_UNQUOTED_KEY}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/apikey").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\": SYNTHETIC_UNQUOTED_KEY}"))
                .andExpect(status().isBadRequest());

        assertFalse(output.getAll().contains("SYNTHETIC_UNQUOTED_KEY"));
        verifyNoInteractions(keys);
    }

    @Test
    void frameworkDebugLoggingOfRequestBodiesNeverShowsKeys(CapturedOutput output) throws Exception {
        var mvcLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        var previous = mvcLogger.getLevel();
        mvcLogger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            mockMvc.perform(put("/api/llm/keys/anthropic").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"apiKey\":\"" + KEY + "\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/apikey").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"apiKey\":\"" + KEY + "\"}"))
                    .andExpect(status().isOk());
        } finally {
            mvcLogger.setLevel(previous);
        }
        assertFalse(output.getAll().contains(KEY));
    }
}
