package com.ratelimiter.model.dto;

import com.ratelimiter.model.domain.PeekResult;

public record PeekResponse(
        String key,
        long currentCount,
        long remaining,
        int limit,
        int windowSeconds,
        long resetInSeconds) {

    public static PeekResponse from(PeekResult result) {
        return new PeekResponse(
                result.key(),
                result.currentCount(),
                result.remaining(),
                result.limit(),
                result.windowSeconds(),
                result.resetInSeconds());
    }
}
