package org.tyler.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.controller.llm.LlmController;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.model.llm.LlmSettings;
import org.tyler.service.llm.ILlmKeyService;
import org.tyler.service.llm.ILlmSettingsService;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests that /api requires the per-launch token, and what stays reachable without it. */
@ExtendWith(OutputCaptureExtension.class)
class ApiAccessTokenFilterTest {

    private static final String TOKEN = "0123456789abcdef".repeat(4);

    private final ILlmSettingsService settings = mock(ILlmSettingsService.class);
    private final ILlmKeyService keys = mock(ILlmKeyService.class);

    private MockMvc mvc(String token) {
        when(settings.get()).thenReturn(LlmSettings.defaults());
        return MockMvcBuilders.standaloneSetup(new LlmController(settings, keys))
                .setControllerAdvice(new GenericExceptionHandler())
                .addFilters(new ApiAccessTokenFilter(token))
                .build();
    }

    @Test
    void theMatchingTokenIsAccepted() throws Exception {
        mvc(TOKEN).perform(get("/api/llm/settings").header(ApiAccessTokenFilter.HEADER, TOKEN))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong", "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0"})
    void aWrongTokenIsRejectedBeforeAnyControllerWork(String supplied, CapturedOutput output) throws Exception {
        mvc(TOKEN).perform(put("/api/llm/keys/openai").header(ApiAccessTokenFilter.HEADER, supplied)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"SYNTHETIC_KEY\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Unauthorized\"}"));
        verifyNoInteractions(keys);
        assertFalse(output.getAll().contains(TOKEN));
    }

    @Test
    void aMissingTokenIsRejected() throws Exception {
        mvc(TOKEN).perform(get("/api/llm/settings")).andExpect(status().isUnauthorized());
        mvc(TOKEN).perform(get("/api")).andExpect(status().isUnauthorized());
        verifyNoInteractions(keys);
    }

    @Test
    void corsPreflightsPassToCorsHandlingWithoutTheToken() throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/llm/settings");
        preflight.addHeader("Origin", "null");
        preflight.addHeader("Access-Control-Request-Method", "PUT");
        MockFilterChain chain = new MockFilterChain();

        new ApiAccessTokenFilter(TOKEN).doFilter(preflight, new MockHttpServletResponse(), chain);

        assertSame(preflight, chain.getRequest());
        // An OPTIONS request that is not a preflight still needs the token.
        mvc(TOKEN).perform(options("/api/llm/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void pathsOutsideTheApiAreNotFiltered() throws Exception {
        mvc(TOKEN).perform(get("/apix")).andExpect(status().is(not(401)));
    }

    @Test
    void withoutAConfiguredTokenTheApiStaysOpen(CapturedOutput output) throws Exception {
        mvc("").perform(get("/api/llm/settings")).andExpect(status().isOk());
        mvc(null).perform(get("/api/llm/settings")).andExpect(status().isOk());
        assertFalse(output.getAll().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg", " "})
    void aMalformedConfiguredTokenStopsStartupWithoutEchoingIt(String token) {
        var error = assertThrows(IllegalStateException.class, () -> new ApiAccessTokenFilter(token));
        assertFalse(error.getMessage().contains("0123456789") || error.getMessage().contains("short"));
    }
}
