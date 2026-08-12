package com.ratelimiter.model.dto;

import com.ratelimiter.model.domain.RateCheckResult;

public record RateCheckResponse(
        boolean allowed,
        String key,
        long currentCount,
        long remaining,
        int limit,
        int windowSeconds,
        long resetInSeconds,
        Boolean degraded) {

    public static RateCheckResponse from(RateCheckResult result) {
        return new RateCheckResponse(
                result.allowed(),
                result.key(),
                result.currentCount(),
                result.remaining(),
                result.limit(),
                result.windowSeconds(),
                result.resetInSeconds(),
                result.degraded() ? Boolean.TRUE : null);
    }
}
