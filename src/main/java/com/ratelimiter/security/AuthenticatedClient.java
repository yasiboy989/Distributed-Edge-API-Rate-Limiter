package com.ratelimiter.security;

public record AuthenticatedClient(String clientId, Role role, int defaultLimit, int defaultWindowSeconds) {
}
