package com.ratelimiter.service;

import com.ratelimiter.exception.ClientNotFoundException;
import com.ratelimiter.model.domain.CreatedApiClient;
import com.ratelimiter.model.entity.ApiClient;
import com.ratelimiter.repository.ApiClientRepository;
import com.ratelimiter.security.ApiKeyCache;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * API keys are high-entropy random secrets, not passwords (§7.2): SHA-256 is
 * sufficient for verification and, unlike BCrypt, doesn't add 50-100ms to the
 * hot path.
 */
@Service
public class KeyManagementService {

    private static final String API_KEY_PREFIX = "ratelimit_sec_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository repository;
    private final ApiKeyCache cache;

    public KeyManagementService(ApiClientRepository repository, ApiKeyCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public CreatedApiClient createClient(String clientName, int defaultLimit, int defaultWindowSeconds) {
        String plaintextApiKey = generateApiKey();
        ApiClient client = ApiClient.builder()
                .clientId(generateClientId())
                .clientName(clientName)
                .apiKeyHash(hash(plaintextApiKey))
                .defaultLimit(defaultLimit)
                .defaultWindowSeconds(defaultWindowSeconds)
                .active(true)
                .createdAt(Instant.now())
                .build();
        client = repository.save(client);
        return new CreatedApiClient(client, plaintextApiKey);
    }

    /**
     * Resolves a presented API key to its client, consulting the cache first
     * (§7.3) so the hot path never hits the DB once warm.
     */
    public Optional<ApiClient> resolve(String presentedApiKey) {
        String hash = hash(presentedApiKey);

        Optional<ApiClient> cached = cache.getPositive(hash);
        if (cached.isPresent()) {
            return cached;
        }
        if (cache.isNegative(hash)) {
            return Optional.empty();
        }

        Optional<ApiClient> found = repository.findByApiKeyHash(hash).filter(ApiClient::isActive);
        if (found.isPresent()) {
            cache.putPositive(hash, found.get());
        } else {
            cache.putNegative(hash);
        }
        return found;
    }

    public void revoke(String clientId) {
        ApiClient client = repository.findByClientId(clientId)
                .orElseThrow(() -> new ClientNotFoundException("Client not found: " + clientId));
        client.setActive(false);
        repository.save(client);
        cache.evict(client.getApiKeyHash());
    }

    private static String generateClientId() {
        return "cli_" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }

    private static String generateApiKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return API_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
