package com.ratelimiter.controller;

import com.ratelimiter.model.domain.CreatedApiClient;
import com.ratelimiter.model.dto.ApiClientSummary;
import com.ratelimiter.model.dto.CreateClientRequest;
import com.ratelimiter.model.dto.CreateClientResponse;
import com.ratelimiter.repository.ApiClientRepository;
import com.ratelimiter.security.AuthenticatedClient;
import com.ratelimiter.service.KeyManagementService;
import com.ratelimiter.service.RateLimiterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin")
public class KeyAdminController {

    private static final String API_KEY_WARNING =
            "Store this API key securely; it will not be shown again.";

    private final KeyManagementService keyManagementService;
    private final ApiClientRepository apiClientRepository;
    private final RateLimiterService rateLimiterService;

    public KeyAdminController(KeyManagementService keyManagementService,
                               ApiClientRepository apiClientRepository,
                               RateLimiterService rateLimiterService) {
        this.keyManagementService = keyManagementService;
        this.apiClientRepository = apiClientRepository;
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateClientResponse createKey(@Valid @RequestBody CreateClientRequest request) {
        CreatedApiClient created = keyManagementService.createClient(
                request.clientName(), request.defaultLimit(), request.defaultWindowSeconds());

        return new CreateClientResponse(
                created.client().getClientId(),
                created.client().getClientName(),
                created.plaintextApiKey(),
                created.client().getDefaultLimit(),
                created.client().getDefaultWindowSeconds(),
                created.client().getCreatedAt(),
                API_KEY_WARNING);
    }

    @GetMapping("/keys")
    public List<ApiClientSummary> listKeys() {
        return apiClientRepository.findAll().stream().map(ApiClientSummary::from).toList();
    }

    @DeleteMapping("/keys/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable String clientId) {
        keyManagementService.revoke(clientId);
    }

    @DeleteMapping("/counters/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetCounter(@AuthenticationPrincipal AuthenticatedClient admin, @PathVariable String key) {
        rateLimiterService.reset(admin.clientId(), key);
    }
}
