package com.ratelimiter.model.dto;

import java.time.Instant;

public record CreateClientResponse(
        String clientId,
        String clientName,
        String apiKey,
        int defaultLimit,
        int defaultWindowSeconds,
        Instant createdAt,
        String warning) {
}
