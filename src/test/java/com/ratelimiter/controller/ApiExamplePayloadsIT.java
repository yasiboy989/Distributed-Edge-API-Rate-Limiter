package com.ratelimiter.controller;

import com.ratelimiter.model.domain.CreatedApiClient;
import com.ratelimiter.service.KeyManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the exact three example payloads from spec section 6 (200 allowed,
 * 429 blocked, and the check-batch mixed-result example) through the real
 * HTTP layer and asserts the documented body shape and status code (section
 * 12 Phase 5 verify). Uses the always-on local Redis container rather than
 * Testcontainers -- see the Phase 2 environment note on this machine's
 * Docker/Testcontainers networking issue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiExamplePayloadsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeyManagementService keyManagementService;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 6379);
    }

    @Test
    void postCheckAllowedMatchesSection6Example() throws Exception {
        String apiKey = createClient();
        String key = "usr_99128_tier1_" + System.nanoTime();

        mockMvc.perform(post("/v1/check")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "%s",
                                  "limit": 100,
                                  "window_seconds": 60,
                                  "cost": 1
                                }
                                """.formatted(key)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "100"))
                .andExpect(header().string("X-RateLimit-Remaining", "99"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.current_count").value(1))
                .andExpect(jsonPath("$.remaining").value(99))
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.window_seconds").value(60))
                .andExpect(jsonPath("$.reset_in_seconds").exists())
                .andExpect(jsonPath("$.degraded").doesNotExist());
    }

    @Test
    void postCheckBlockedMatchesSection6Example() throws Exception {
        String apiKey = createClient();
        String key = "usr_99128_tier1_" + System.nanoTime();
        String body = """
                {
                  "key": "%s",
                  "limit": 1,
                  "window_seconds": 60,
                  "cost": 1
                }
                """.formatted(key);

        mockMvc.perform(post("/v1/check").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/check").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "1"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.current_count").value(1))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.window_seconds").value(60))
                .andExpect(jsonPath("$.reset_in_seconds").exists());
    }

    @Test
    void postCheckBatchMatchesSection6Example() throws Exception {
        String apiKey = createClient();
        String keyA = "usr_99128_tier1_" + System.nanoTime();
        String keyB = "ip_203_0_113_19_" + System.nanoTime();

        // pre-exhaust keyB's tiny limit so the batch produces a mixed result,
        // exactly like the spec's example (one allowed, one blocked)
        mockMvc.perform(post("/v1/check").header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "key": "%s", "limit": 10, "window_seconds": 1, "cost": 1 }
                        """.formatted(keyB)));

        mockMvc.perform(post("/v1/check-batch")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requests": [
                                    { "key": "%s", "limit": 100, "window_seconds": 60, "cost": 1 },
                                    { "key": "%s", "limit": 10,  "window_seconds": 1,  "cost": 10 }
                                  ]
                                }
                                """.formatted(keyA, keyB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.results[0].key").value(keyA))
                .andExpect(jsonPath("$.results[0].allowed").value(true))
                .andExpect(jsonPath("$.results[1].key").value(keyB))
                .andExpect(jsonPath("$.results[1].allowed").value(false))
                .andExpect(jsonPath("$.all_allowed").value(false));
    }

    @Test
    void adminCanResetTheirOwnCounter() throws Exception {
        String bootstrapKey = "test_bootstrap_key"; // matches application-test.yml
        String key = "usr_reset_it_" + System.nanoTime();

        mockMvc.perform(delete("/v1/admin/counters/" + key).header("X-API-Key", bootstrapKey))
                .andExpect(status().isNoContent());
    }

    private String createClient() {
        CreatedApiClient created = keyManagementService.createClient("Example Payload Client", 1000, 3600);
        return created.plaintextApiKey();
    }
}
