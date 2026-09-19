package com.dataanalyst.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-style tests for CORS configuration (requirement 8.8).
 *
 * <p>Verifies that pre-flight OPTIONS requests from the Angular dev origin
 * ({@code http://localhost:4200}) receive the correct CORS response headers
 * on {@code /api/**} endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromAngularDevOrigin_shouldReturnCorsHeaders() throws Exception {
        mockMvc.perform(
                options("/api/analysis")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().exists("Access-Control-Allow-Methods"));
    }

    @Test
    void preflightFromDisallowedOrigin_shouldNotIncludeAllowOriginHeader() throws Exception {
        mockMvc.perform(
                options("/api/analysis")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightOnNonApiPath_shouldNotIncludeCorsHeaders() throws Exception {
        // CORS mapping is restricted to /api/** only; other paths should not
        // return CORS allow headers even for the permitted Angular dev origin.
        mockMvc.perform(
                options("/health")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
