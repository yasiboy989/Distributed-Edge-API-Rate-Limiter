package com.ratelimiter.model.domain;

public record PeekResult(
        String key,
        long currentCount,
        long remaining,
        int limit,
        int windowSeconds,
        long resetInSeconds) {
}
