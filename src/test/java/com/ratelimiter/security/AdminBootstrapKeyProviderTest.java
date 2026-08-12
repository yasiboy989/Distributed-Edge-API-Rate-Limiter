package com.ratelimiter.security;

import com.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminBootstrapKeyProviderTest {

    @Test
    void refusesToConstructInProdWithoutBootstrapKey() {
        RateLimiterProperties properties = properties(null);
        MockEnvironment env = mockEnv("prod");

        assertThatThrownBy(() -> new AdminBootstrapKeyProvider(properties, env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RATELIMITER_ADMIN_BOOTSTRAP_KEY");
    }

    @Test
    void generatesRandomKeyInDevWhenUnset() {
        RateLimiterProperties properties = properties(null);
        MockEnvironment env = mockEnv("dev");

        AdminBootstrapKeyProvider provider = new AdminBootstrapKeyProvider(properties, env);

        assertThat(provider.get()).isNotBlank();
    }

    @Test
    void usesConfiguredKeyWhenPresentEvenInProd() {
        RateLimiterProperties properties = properties("configured-key");
        MockEnvironment env = mockEnv("prod");

        AdminBootstrapKeyProvider provider = new AdminBootstrapKeyProvider(properties, env);

        assertThat(provider.get()).isEqualTo("configured-key");
    }

    private static RateLimiterProperties properties(String bootstrapKey) {
        return new RateLimiterProperties(
                new RateLimiterProperties.Admin(bootstrapKey),
                new RateLimiterProperties.Redis(com.ratelimiter.model.domain.FailureMode.FAIL_OPEN),
                new RateLimiterProperties.Batch(50));
    }

    private static MockEnvironment mockEnv(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }
}
