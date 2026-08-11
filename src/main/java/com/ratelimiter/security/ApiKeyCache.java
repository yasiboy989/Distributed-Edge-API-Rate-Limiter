package com.ratelimiter.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.model.entity.ApiClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Caches API-key-hash to client lookups so the hot path (§6.1) never hits the
 * DB. Negative results are cached too, on a short TTL, so a flood of invalid
 * keys cannot hammer the DB (§7.3).
 */
@Component
public class ApiKeyCache {

    private final Cache<String, ApiClient> positive = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .recordStats()
            .build();

    private final Cache<String, Boolean> negative = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(10))
            .recordStats()
            .build();

    public Optional<ApiClient> getPositive(String apiKeyHash) {
        return Optional.ofNullable(positive.getIfPresent(apiKeyHash));
    }

    public void putPositive(String apiKeyHash, ApiClient client) {
        positive.put(apiKeyHash, client);
    }

    public boolean isNegative(String apiKeyHash) {
        return negative.getIfPresent(apiKeyHash) != null;
    }

    public void putNegative(String apiKeyHash) {
        negative.put(apiKeyHash, Boolean.TRUE);
    }

    public void evict(String apiKeyHash) {
        positive.invalidate(apiKeyHash);
        negative.invalidate(apiKeyHash);
    }
}
