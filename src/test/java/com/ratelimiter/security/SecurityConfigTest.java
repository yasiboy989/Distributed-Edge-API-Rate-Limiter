package com.ratelimiter.security;

import com.ratelimiter.model.domain.CreatedApiClient;
import com.ratelimiter.service.KeyManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §12 Phase 4 verify: no key -> 401; bootstrap key -> passes; client key on
 * /v1/admin/** -> 403. No controllers exist yet (Phase 5), so these hit
 * unmapped paths deliberately: Spring Security's filter chain runs before
 * DispatcherServlet, so a 401/403 here proves security decided first, while
 * a 404 proves security let the request through to (nonexistent) dispatch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    private static final String BOOTSTRAP_KEY = "test_bootstrap_key"; // matches application-test.yml

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeyManagementService keyManagementService;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void missingApiKeyIsRejected() throws Exception {
        mockMvc.perform(get("/v1/some-endpoint"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Missing API key"));
    }

    @Test
    void invalidApiKeyIsRejected() throws Exception {
        mockMvc.perform(get("/v1/some-endpoint").header("X-API-Key", "ratelimit_sec_not-a-real-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid API key"));
    }

    @Test
    void bootstrapAdminKeyPassesSecurityOnAdminPath() throws Exception {
        mockMvc.perform(get("/v1/admin/some-endpoint").header("X-API-Key", BOOTSTRAP_KEY))
                .andExpect(status().isNotFound()); // security allowed it through; no controller mapped yet
    }

    @Test
    void bootstrapAdminKeyPassesSecurityOnCheckPath() throws Exception {
        mockMvc.perform(get("/v1/some-endpoint").header("X-API-Key", BOOTSTRAP_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void clientKeyIsForbiddenOnAdminPath() throws Exception {
        CreatedApiClient created = keyManagementService.createClient("Test Client", 100, 60);

        mockMvc.perform(get("/v1/admin/some-endpoint").header("X-API-Key", created.plaintextApiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Insufficient privileges"));
    }

    @Test
    void clientKeyPassesSecurityOnCheckPath() throws Exception {
        CreatedApiClient created = keyManagementService.createClient("Test Client 2", 100, 60);

        mockMvc.perform(get("/v1/some-endpoint").header("X-API-Key", created.plaintextApiKey()))
                .andExpect(status().isNotFound()); // security allowed it through; no controller mapped yet
    }
}
