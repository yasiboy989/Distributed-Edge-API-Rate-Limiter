package com.ratelimiter.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class RedisKeyBuilder {

    private static final Pattern USER_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9:_.\\-]{1,128}$");

    public String build(String clientId, String userKey) {
        if (userKey == null || !USER_KEY_PATTERN.matcher(userKey).matches()) {
            throw new IllegalArgumentException("key must match " + USER_KEY_PATTERN.pattern());
        }
        return "rl:" + clientId + ":" + userKey;
    }
}
