package org.tyler.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies that cross-origin API access is limited to the Electron file origin. */
class CorsConfigTest {

    @Test
    void allowsOnlyTheElectronOrigin() {
        TestCorsRegistry registry = new TestCorsRegistry();
        new CorsConfig().addCorsMappings(registry);
        CorsConfiguration config = registry.apiConfiguration();

        assertEquals("null", config.checkOrigin("null"));
        assertNull(config.checkOrigin("http://127.0.0.1:5173"));
        assertNull(config.checkOrigin("http://localhost:5173"));
        assertNull(config.checkOrigin("http://192.168.1.10:5173"));
        assertNull(config.checkOrigin("https://example.com"));
    }

    private static final class TestCorsRegistry extends CorsRegistry {
        private CorsConfiguration apiConfiguration() {
            return getCorsConfigurations().get("/api/**");
        }
    }
}
