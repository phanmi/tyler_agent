package org.tyler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration.
 *
 * <p>Development requests use Vite's same-origin /api proxy, regardless of
 * the frontend port. Electron loads dist/index.html from a file:// URL and
 * calls the backend on its assigned loopback port, which requires CORS.
 *
 * <p>CORS applies only to /api/** and does not allow credentials. The file origin
 * is represented as {@code null} in browser CORS requests.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("null")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
