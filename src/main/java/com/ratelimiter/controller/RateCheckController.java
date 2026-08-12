package com.ratelimiter.controller;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.exception.BatchTooLargeException;
import com.ratelimiter.exception.CostExceedsLimitException;
import com.ratelimiter.model.domain.PeekResult;
import com.ratelimiter.model.domain.RateCheckCommand;
import com.ratelimiter.model.domain.RateCheckResult;
import com.ratelimiter.model.dto.BatchCheckRequest;
import com.ratelimiter.model.dto.BatchCheckResponse;
import com.ratelimiter.model.dto.PeekResponse;
import com.ratelimiter.model.dto.RateCheckRequest;
import com.ratelimiter.model.dto.RateCheckResponse;
import com.ratelimiter.security.AuthenticatedClient;
import com.ratelimiter.service.RateLimiterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
@Validated
public class RateCheckController {

    private final RateLimiterService rateLimiterService;
    private final RateLimiterProperties properties;

    public RateCheckController(RateLimiterService rateLimiterService, RateLimiterProperties properties) {
        this.rateLimiterService = rateLimiterService;
        this.properties = properties;
    }

    @PostMapping("/check")
    public ResponseEntity<RateCheckResponse> check(@AuthenticationPrincipal AuthenticatedClient client,
                                                     @Valid @RequestBody RateCheckRequest request) {
        int limit = effectiveLimit(request.limit(), client);
        int windowSeconds = effectiveWindowSeconds(request.windowSeconds(), client);
        requireCostWithinLimit(request.cost(), limit);

        RateCheckResult result = rateLimiterService.check(client.clientId(), request.key(), limit, windowSeconds, request.cost());
        RateCheckResponse body = RateCheckResponse.from(result);
        HttpHeaders headers = rateLimitHeaders(result);

        HttpStatus status = result.allowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS;
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    @PostMapping("/check-batch")
    public BatchCheckResponse checkBatch(@AuthenticationPrincipal AuthenticatedClient client,
                                          @Valid @RequestBody BatchCheckRequest request) {
        int maxSize = properties.batch().maxSize();
        if (request.requests().size() > maxSize) {
            throw new BatchTooLargeException("Batch exceeds the maximum of " + maxSize + " entries");
        }

        List<RateCheckCommand> commands = request.requests().stream()
                .map(r -> {
                    int limit = effectiveLimit(r.limit(), client);
                    int windowSeconds = effectiveWindowSeconds(r.windowSeconds(), client);
                    requireCostWithinLimit(r.cost(), limit);
                    return new RateCheckCommand(r.key(), limit, windowSeconds, r.cost());
                })
                .toList();

        List<RateCheckResult> results = rateLimiterService.checkBatch(client.clientId(), commands);
        List<RateCheckResponse> responses = results.stream().map(RateCheckResponse::from).toList();
        boolean allAllowed = results.stream().allMatch(RateCheckResult::allowed);
        return new BatchCheckResponse(responses, allAllowed);
    }

    @GetMapping("/check/{key}")
    public PeekResponse peek(@AuthenticationPrincipal AuthenticatedClient client,
                              @PathVariable @Pattern(regexp = "^[A-Za-z0-9:_.\\-]{1,128}$") String key,
                              @RequestParam(required = false) @Min(1) @Max(1_000_000) Integer limit,
                              @RequestParam(name = "window_seconds", required = false) @Min(1) @Max(86_400) Integer windowSeconds) {
        int effLimit = effectiveLimit(limit, client);
        int effWindowSeconds = effectiveWindowSeconds(windowSeconds, client);
        PeekResult result = rateLimiterService.peek(client.clientId(), key, effLimit, effWindowSeconds);
        return PeekResponse.from(result);
    }

    private static int effectiveLimit(Integer requested, AuthenticatedClient client) {
        return requested != null ? requested : client.defaultLimit();
    }

    private static int effectiveWindowSeconds(Integer requested, AuthenticatedClient client) {
        return requested != null ? requested : client.defaultWindowSeconds();
    }

    private static void requireCostWithinLimit(int cost, int limit) {
        if (cost > limit) {
            throw new CostExceedsLimitException();
        }
    }

    private static HttpHeaders rateLimitHeaders(RateCheckResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Limit", String.valueOf(result.limit()));
        headers.add("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        headers.add("X-RateLimit-Reset", String.valueOf(result.resetInSeconds()));
        if (!result.allowed()) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(result.resetInSeconds()));
        }
        return headers;
    }
}
