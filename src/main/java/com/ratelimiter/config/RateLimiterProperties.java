package com.ratelimiter.config;

import com.ratelimiter.model.domain.FailureMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(Admin admin, Redis redis, Batch batch) {

    public record Admin(String bootstrapKey) {
    }

    public record Redis(FailureMode failureMode) {
    }

    public record Batch(int maxSize) {
    }
}
