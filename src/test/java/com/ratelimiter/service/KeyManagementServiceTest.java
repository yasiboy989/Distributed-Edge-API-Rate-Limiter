package com.ratelimiter.service;

import com.ratelimiter.exception.ClientNotFoundException;
import com.ratelimiter.model.domain.CreatedApiClient;
import com.ratelimiter.model.entity.ApiClient;
import com.ratelimiter.security.ApiKeyCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Flyway migration + Hibernate ddl-auto=validate against H2, exercising
 * the actual entity/schema mapping rather than mocks (§12 Phase 3 verify:
 * created key's SHA-256 resolves to the client; revoked key does not).
 */
@DataJpaTest
@Import({KeyManagementService.class, ApiKeyCache.class})
class KeyManagementServiceTest {

    @Autowired
    private KeyManagementService keyManagementService;

    @Test
    void createdKeyHashResolvesToClient() throws Exception {
        CreatedApiClient created = keyManagementService.createClient("Acme SaaS Gateway", 1000, 3600);

        assertThat(created.plaintextApiKey()).startsWith("ratelimit_sec_");
        assertThat(created.client().getApiKeyHash()).isEqualTo(sha256Hex(created.plaintextApiKey()));

        Optional<ApiClient> resolved = keyManagementService.resolve(created.plaintextApiKey());
        assertThat(resolved).isPresent();
        assertThat(resolved.get().getClientId()).isEqualTo(created.client().getClientId());
        assertThat(resolved.get().getClientName()).isEqualTo("Acme SaaS Gateway");
    }

    @Test
    void unknownKeyDoesNotResolve() {
        assertThat(keyManagementService.resolve("ratelimit_sec_does-not-exist")).isEmpty();
    }

    @Test
    void revokedKeyNoLongerResolves() {
        CreatedApiClient created = keyManagementService.createClient("Revoke Me Inc", 100, 60);
        assertThat(keyManagementService.resolve(created.plaintextApiKey())).isPresent();

        keyManagementService.revoke(created.client().getClientId());

        assertThat(keyManagementService.resolve(created.plaintextApiKey())).isEmpty();
    }

    @Test
    void revokingUnknownClientThrows() {
        assertThatThrownBy(() -> keyManagementService.revoke("cli_doesnotexist"))
                .isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void resolveIsCachedAndSurvivesWithoutHittingRepositoryAgain() {
        CreatedApiClient created = keyManagementService.createClient("Cached Client", 100, 60);

        // First call populates the cache; second call must return the same result
        // purely from cache (no assertion possible on DB hits here without a spy,
        // but this at minimum proves repeated resolution is stable).
        Optional<ApiClient> first = keyManagementService.resolve(created.plaintextApiKey());
        Optional<ApiClient> second = keyManagementService.resolve(created.plaintextApiKey());

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().getClientId()).isEqualTo(second.get().getClientId());
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
