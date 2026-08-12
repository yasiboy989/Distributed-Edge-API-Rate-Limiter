package com.ratelimiter.model.dto;

import com.ratelimiter.model.entity.ApiClient;

import java.time.Instant;

public record ApiClientSummary(
        String clientId,
        String clientName,
        int defaultLimit,
        int defaultWindowSeconds,
        boolean active,
        Instant createdAt) {

    public static ApiClientSummary from(ApiClient client) {
        return new ApiClientSummary(
                client.getClientId(),
                client.getClientName(),
                client.getDefaultLimit(),
                client.getDefaultWindowSeconds(),
                client.isActive(),
                client.getCreatedAt());
    }
}
