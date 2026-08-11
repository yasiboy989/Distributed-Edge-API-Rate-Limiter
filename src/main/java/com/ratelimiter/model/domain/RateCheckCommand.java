package com.ratelimiter.model.domain;

public record RateCheckCommand(String key, int limit, int windowSeconds, int cost) {
}
