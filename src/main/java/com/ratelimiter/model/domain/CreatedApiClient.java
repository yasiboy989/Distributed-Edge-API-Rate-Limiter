package com.ratelimiter.model.domain;

import com.ratelimiter.model.entity.ApiClient;

/**
 * The plaintext API key is only ever available at creation time (§7.2) — it is
 * never persisted or logged, only its SHA-256 hash is.
 */
public record CreatedApiClient(ApiClient client, String plaintextApiKey) {
}
