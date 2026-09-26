package org.tyler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration.
 *
 * <p>The frontend runs in two environments:
 * <ul>
 *   <li>Development: Vite (http://localhost:5173) proxies /api requests,
 *       keeping them same-origin from the browser's perspective.</li>
 *   <li>Production (Electron): the main process loads dist/index.html through loadFile,
 *       giving it a file:// origin. Calls to http://127.0.0.1:8080/api/** are cross-origin
 *       and require backend CORS support.</li>
 * </ul>
 *
 * <p>CORS applies only to /api/** and does not allow credentials.
 * Future hardening can restrict the listening address and allowed origins.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}