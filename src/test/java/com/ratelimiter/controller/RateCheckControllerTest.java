package com.ratelimiter.controller;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.config.SecurityConfig;
import com.ratelimiter.model.domain.FailureMode;
import com.ratelimiter.model.domain.RateCheckResult;
import com.ratelimiter.model.entity.ApiClient;
import com.ratelimiter.security.AdminBootstrapKeyProvider;
import com.ratelimiter.service.KeyManagementService;
import com.ratelimiter.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-only slice (mocked RateLimiterService, no Redis) per section 12
 * Phase 6: asserts 200/429, header presence, 400 on bad input.
 */
@WebMvcTest(RateCheckController.class)
@Import({SecurityConfig.class, RateCheckControllerTest.TestConfig.class})
class RateCheckControllerTest {

    private static final String API_KEY = "ratelimit_sec_test-key";
    private static final String CLIENT_ID = "cli_test123";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RateLimiterService rateLimiterService;

    @MockBean
    private KeyManagementService keyManagementService;

    @MockBean
    private AdminBootstrapKeyProvider bootstrapKeyProvider;

    @TestConfiguration
    static class TestConfig {
        @Bean
        RateLimiterProperties rateLimiterProperties() {
            return new RateLimiterProperties(
                    new RateLimiterProperties.Admin("unused-in-this-test"),
                    new RateLimiterProperties.Redis(FailureMode.FAIL_OPEN),
                    new RateLimiterProperties.Batch(50));
        }
    }

    private void authenticateAsClient(int defaultLimit, int defaultWindowSeconds) {
        ApiClient client = ApiClient.builder()
                .clientId(CLIENT_ID)
                .clientName("Test Client")
                .apiKeyHash("unused")
                .defaultLimit(defaultLimit)
                .defaultWindowSeconds(defaultWindowSeconds)
                .active(true)
                .createdAt(Instant.now())
                .build();
        when(keyManagementService.resolve(API_KEY)).thenReturn(Optional.of(client));
        when(bootstrapKeyProvider.get()).thenReturn("unused-bootstrap-key");
    }

    @Test
    void allowedRequestReturns200WithHeaders() throws Exception {
        authenticateAsClient(100, 60);
        when(rateLimiterService.check(eq(CLIENT_ID), eq("usr1"), eq(100), eq(60), eq(1)))
                .thenReturn(RateCheckResult.of(true, "usr1", 14, 86, 100, 60, 42));

        mockMvc.perform(post("/v1/check")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key": "usr1", "limit": 100, "window_seconds": 60, "cost": 1}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "100"))
                .andExpect(header().string("X-RateLimit-Remaining", "86"))
                .andExpect(header().string("X-RateLimit-Reset", "42"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.current_count").value(14))
                .andExpect(jsonPath("$.remaining").value(86));
    }

    @Test
    void blockedRequestReturns429WithRetryAfter() throws Exception {
        authenticateAsClient(100, 60);
        when(rateLimiterService.check(eq(CLIENT_ID), eq("usr1"), eq(100), eq(60), eq(1)))
                .thenReturn(RateCheckResult.of(false, "usr1", 100, 0, 100, 60, 18));

        mockMvc.perform(post("/v1/check")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key": "usr1", "limit": 100, "window_seconds": 60, "cost": 1}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "18"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.remaining").value(0));
    }

    @Test
    void blankKeyReturns400() throws Exception {
        authenticateAsClient(100, 60);

        mockMvc.perform(post("/v1/check")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key": "", "limit": 100, "window_seconds": 60, "cost": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void costGreaterThanExplicitLimitReturns400() throws Exception {
        authenticateAsClient(100, 60);

        mockMvc.perform(post("/v1/check")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key": "usr1", "limit": 10, "window_seconds": 60, "cost": 50}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("cost"));
    }
}
