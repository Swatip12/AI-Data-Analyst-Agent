package com.dataanalyst.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Startup guard that verifies the OpenAI API key is present before the
 * application begins serving requests.
 *
 * <p>Implements requirement 13.8: if the LLM API key environment variable or
 * configuration value is absent at application startup, the application SHALL
 * fail to start and log an ERROR-level message stating that the LLM API key
 * is not configured.
 *
 * <p>The key is resolved from the {@code openai.api-key} property, which should
 * be supplied via an environment variable ({@code OPENAI_API_KEY}) or an
 * externalized {@code application-secrets.yml} file (which is gitignored).
 * No hardcoded key value is ever present in source code (requirement 13.2).
 */
@Component
public class OpenAiApiKeyGuard implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(OpenAiApiKeyGuard.class);

    /**
     * The LLM API key injected exclusively from externalized configuration.
     * Defaults to an empty string so that the guard (not the framework) controls
     * the failure message and exit behavior.
     */
    @Value("${openai.api-key:}")
    private String openAiApiKey;

    /**
     * Called once the application context is fully refreshed and the server
     * is ready to accept requests. If the API key is absent or blank, an
     * ERROR is logged and an {@link IllegalStateException} is thrown to
     * abort startup cleanly.
     *
     * @param event the {@link ApplicationReadyEvent} fired by Spring Boot
     * @throws IllegalStateException if {@code openai.api-key} is blank
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            log.error(
                "APPLICATION STARTUP FAILED: The LLM API key is not configured. " +
                "Set the 'openai.api-key' property via the OPENAI_API_KEY environment variable " +
                "or in application-secrets.yml (which must NOT be committed to version control)."
            );
            throw new IllegalStateException(
                "LLM API key (openai.api-key) is absent or blank. " +
                "The application cannot start without a valid API key."
            );
        }
        log.info("OpenAI API key guard: API key is present — startup check passed.");
    }
}
