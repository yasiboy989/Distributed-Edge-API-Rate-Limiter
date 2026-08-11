package com.ratelimiter.security;

import com.ratelimiter.config.RateLimiterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Resolves the effective admin bootstrap key (§7.1). Solves the
 * chicken-and-egg problem of minting the first client key: the admin key
 * comes from configuration, not the database.
 */
@Component
public class AdminBootstrapKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapKeyProvider.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String bootstrapKey;

    public AdminBootstrapKeyProvider(RateLimiterProperties properties, Environment environment) {
        String configured = properties.admin().bootstrapKey();
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        if (configured == null || configured.isBlank()) {
            if (isProd) {
                throw new IllegalStateException(
                        "RATELIMITER_ADMIN_BOOTSTRAP_KEY must be set when running with the 'prod' profile");
            }
            configured = generateRandomKey();
            log.info("No admin bootstrap key configured; generated one for this session: {}", configured);
        }
        this.bootstrapKey = configured;
    }

    public String get() {
        return bootstrapKey;
    }

    private static String generateRandomKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
