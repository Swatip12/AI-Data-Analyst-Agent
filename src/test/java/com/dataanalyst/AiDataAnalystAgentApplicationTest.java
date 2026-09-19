package com.dataanalyst;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.dataanalyst.config.OpenAiApiKeyGuard;

/**
 * Smoke test — verifies the Spring application context loads successfully
 * when a valid API key is present (supplied via test application.yml).
 */
@SpringBootTest
@ActiveProfiles("test")
class AiDataAnalystAgentApplicationTest {

    @Autowired
    private OpenAiApiKeyGuard openAiApiKeyGuard;

    @Test
    void contextLoads() {
        // If the context loads, package structure and configuration are valid.
        assertThat(openAiApiKeyGuard).isNotNull();
    }
}
