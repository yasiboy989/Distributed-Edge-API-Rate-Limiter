package com.ratelimiter.model.domain;

public record RateCheckResult(
        boolean allowed,
        String key,
        long currentCount,
        long remaining,
        int limit,
        int windowSeconds,
        long resetInSeconds,
        boolean degraded) {

    public static RateCheckResult of(boolean allowed, String key, long currentCount, long remaining,
                                      int limit, int windowSeconds, long resetInSeconds) {
        return new RateCheckResult(allowed, key, currentCount, remaining, limit, windowSeconds, resetInSeconds, false);
    }

    public static RateCheckResult degraded(String key, int limit, int windowSeconds) {
        return new RateCheckResult(true, key, -1, -1, limit, windowSeconds, 0, true);
    }
}
