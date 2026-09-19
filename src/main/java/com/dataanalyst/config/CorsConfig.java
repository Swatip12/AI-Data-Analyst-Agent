package com.dataanalyst.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration for the AI Data Analyst Agent API.
 *
 * <p>Allows cross-origin requests from the Angular development server
 * ({@code http://localhost:4200}) and an optional production domain
 * to all {@code /api/**} endpoints (requirement 8.8).
 *
 * <p>The production origin is configured via the {@code cors.allowed-origin-prod}
 * property (defaults to empty, which is excluded when blank). Override in
 * {@code application-secrets.yml} or via environment variable for production
 * deployments.
 *
 * <p>Only the HTTP methods used by the Angular client are permitted:
 * {@code GET}, {@code POST}, {@code OPTIONS} (for pre-flight). Credentials
 * are allowed so that the Angular client can send the session cookie or
 * Authorization header if needed in the future.
 */
@Configuration
public class CorsConfig {

    private static final String ANGULAR_DEV_ORIGIN = "http://localhost:4200";

    /**
     * Optional production domain, e.g. {@code https://www.example.com}.
     * When blank (the default), no production origin is registered.
     */
    @Value("${cors.allowed-origin-prod:}")
    private String productionOrigin;

    /**
     * Registers CORS mappings for all {@code /api/**} routes.
     *
     * @return a {@link WebMvcConfigurer} that applies the CORS rules
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String[] origins = buildAllowedOrigins();

                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(3600); // pre-flight cache: 1 hour
            }
        };
    }

    /**
     * Builds the list of allowed origins. The Angular dev server is always
     * included; the production origin is added only when non-blank.
     *
     * @return array of allowed origin strings
     */
    private String[] buildAllowedOrigins() {
        if (productionOrigin != null && !productionOrigin.isBlank()) {
            return new String[]{ANGULAR_DEV_ORIGIN, productionOrigin.trim()};
        }
        return new String[]{ANGULAR_DEV_ORIGIN};
    }
}
