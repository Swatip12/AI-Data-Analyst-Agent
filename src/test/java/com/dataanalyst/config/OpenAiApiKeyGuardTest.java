package com.dataanalyst.config;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.mock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link OpenAiApiKeyGuard}.
 *
 * Verifies requirement 13.8: application must fail to start and log ERROR
 * when the LLM API key is absent or blank.
 */
class OpenAiApiKeyGuardTest {

    private final ApplicationReadyEvent dummyEvent = mock(ApplicationReadyEvent.class);

    private OpenAiApiKeyGuard guardWithKey(String key) {
        OpenAiApiKeyGuard guard = new OpenAiApiKeyGuard();
        ReflectionTestUtils.setField(guard, "openAiApiKey", key);
        return guard;
    }

    // --- Requirement 13.8: absent or blank key must abort startup ---

    @Test
    void whenApiKeyIsNull_thenThrowsIllegalStateException() {
        OpenAiApiKeyGuard guard = guardWithKey(null);
        assertThatThrownBy(() -> guard.onApplicationEvent(dummyEvent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LLM API key");
    }

    @Test
    void whenApiKeyIsEmpty_thenThrowsIllegalStateException() {
        OpenAiApiKeyGuard guard = guardWithKey("");
        assertThatThrownBy(() -> guard.onApplicationEvent(dummyEvent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LLM API key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "\t", "\n", "  \t  \n  "})
    void whenApiKeyIsBlank_thenThrowsIllegalStateException(String blankKey) {
        OpenAiApiKeyGuard guard = guardWithKey(blankKey);
        assertThatThrownBy(() -> guard.onApplicationEvent(dummyEvent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LLM API key");
    }

    // --- Happy path: valid key allows startup ---

    @Test
    void whenApiKeyIsPresent_thenNoExceptionThrown() {
        OpenAiApiKeyGuard guard = guardWithKey("sk-test-valid-api-key");
        assertThatNoException().isThrownBy(() -> guard.onApplicationEvent(dummyEvent));
    }

    @Test
    void whenApiKeyIsMinimalNonBlank_thenNoExceptionThrown() {
        OpenAiApiKeyGuard guard = guardWithKey("x");
        assertThatNoException().isThrownBy(() -> guard.onApplicationEvent(dummyEvent));
    }
}
