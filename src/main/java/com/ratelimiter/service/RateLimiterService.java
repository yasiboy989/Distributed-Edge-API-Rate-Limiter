package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.exception.RateLimiterUnavailableException;
import com.ratelimiter.model.domain.FailureMode;
import com.ratelimiter.model.domain.PeekResult;
import com.ratelimiter.model.domain.RateCheckCommand;
import com.ratelimiter.model.domain.RateCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hot-path rate limiting via a single atomic Redis Lua script (§5). Redis is
 * the authoritative clock; see §5.2 for why. On Redis failure, behaviour is
 * governed by {@link FailureMode} (§8) — this policy applies to the /v1/check
 * hot path (check/checkBatch); peek and reset are control-plane reads and
 * simply surface Redis failures.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> slidingWindowScript;
    private final RedisScript<List> peekScript;
    private final RedisKeyBuilder redisKeyBuilder;
    private final FailureMode failureMode;

    public RateLimiterService(StringRedisTemplate redisTemplate,
                               RedisScript<List> slidingWindowScript,
                               RedisScript<List> peekScript,
                               RedisKeyBuilder redisKeyBuilder,
                               RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
        this.peekScript = peekScript;
        this.redisKeyBuilder = redisKeyBuilder;
        this.failureMode = properties.redis().failureMode();
    }

    public RateCheckResult check(String clientId, String key, int limit, int windowSeconds, int cost) {
        String redisKey = redisKeyBuilder.build(clientId, key);
        String requestId = UUID.randomUUID().toString();
        try {
            List<?> result = redisTemplate.execute(slidingWindowScript, List.of(redisKey),
                    String.valueOf(windowSeconds), String.valueOf(limit), String.valueOf(cost), requestId, "");
            return toRateCheckResult(result, key, limit, windowSeconds);
        } catch (RedisConnectionFailureException | QueryTimeoutException | RedisSystemException e) {
            return handleCheckFailure(key, limit, windowSeconds, e);
        }
    }

    public List<RateCheckResult> checkBatch(String clientId, List<RateCheckCommand> commands) {
        byte[] scriptBody = slidingWindowScript.getScriptAsString().getBytes(StandardCharsets.UTF_8);
        String sha = slidingWindowScript.getSha1();
        List<String> requestIds = new ArrayList<>(commands.size());

        List<Object> raw;
        try {
            raw = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.scriptingCommands().scriptLoad(scriptBody);
                for (RateCheckCommand cmd : commands) {
                    String requestId = UUID.randomUUID().toString();
                    requestIds.add(requestId);
                    String redisKey = redisKeyBuilder.build(clientId, cmd.key());
                    connection.scriptingCommands().evalSha(sha, ReturnType.MULTI, 1,
                            bytes(redisKey), bytes(String.valueOf(cmd.windowSeconds())),
                            bytes(String.valueOf(cmd.limit())), bytes(String.valueOf(cmd.cost())),
                            bytes(requestId), bytes(""));
                }
                return null;
            });
        } catch (RedisConnectionFailureException | QueryTimeoutException | RedisSystemException e) {
            return commands.stream()
                    .map(cmd -> handleCheckFailure(cmd.key(), cmd.limit(), cmd.windowSeconds(), e))
                    .toList();
        }

        // raw[0] is the SCRIPT LOAD reply; one evalSha reply per command follows, in order.
        List<RateCheckResult> results = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            RateCheckCommand cmd = commands.get(i);
            Object element = raw.get(i + 1);
            if (element instanceof List<?> list) {
                results.add(toRateCheckResult(list, cmd.key(), cmd.limit(), cmd.windowSeconds()));
            } else {
                results.add(handleCheckFailure(cmd.key(), cmd.limit(), cmd.windowSeconds(),
                        new IllegalStateException("Malformed pipelined script result: " + element)));
            }
        }
        return results;
    }

    public PeekResult peek(String clientId, String key, int limit, int windowSeconds) {
        String redisKey = redisKeyBuilder.build(clientId, key);
        List<?> result = redisTemplate.execute(peekScript, List.of(redisKey),
                String.valueOf(windowSeconds), String.valueOf(limit), "");
        if (result == null || result.size() != 3) {
            throw new RateLimiterUnavailableException("Malformed peek script result", null);
        }
        long currentCount = ((Number) result.get(0)).longValue();
        long remaining = ((Number) result.get(1)).longValue();
        long resetInSeconds = ((Number) result.get(2)).longValue();
        return new PeekResult(key, currentCount, remaining, limit, windowSeconds, resetInSeconds);
    }

    public void reset(String clientId, String key) {
        String redisKey = redisKeyBuilder.build(clientId, key);
        redisTemplate.delete(redisKey);
    }

    private RateCheckResult toRateCheckResult(List<?> result, String key, int limit, int windowSeconds) {
        if (result == null || result.size() != 4) {
            return handleCheckFailure(key, limit, windowSeconds,
                    new IllegalStateException("Malformed sliding_window.lua result: " + result));
        }
        boolean allowed = ((Number) result.get(0)).longValue() == 1L;
        long currentCount = ((Number) result.get(1)).longValue();
        long remaining = ((Number) result.get(2)).longValue();
        long resetInSeconds = ((Number) result.get(3)).longValue();
        return RateCheckResult.of(allowed, key, currentCount, remaining, limit, windowSeconds, resetInSeconds);
    }

    private RateCheckResult handleCheckFailure(String key, int limit, int windowSeconds, Exception cause) {
        log.warn("Redis rate-limit check failed for key={}, applying failure-mode={}", key, failureMode, cause);
        if (failureMode == FailureMode.FAIL_CLOSED) {
            throw new RateLimiterUnavailableException("Rate limiter unavailable", cause);
        }
        return RateCheckResult.degraded(key, limit, windowSeconds);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
