package com.ratelimiter.service;

import com.ratelimiter.model.domain.RateCheckCommand;
import com.ratelimiter.model.domain.RateCheckResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("docker")
@Testcontainers
@SpringBootTest
class RateLimiterServiceIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RateLimiterService rateLimiterService;

    @Test
    void allowsExactlyUpToLimitThenBlocks() {
        String clientId = "test-client";
        String key = "usr_" + System.nanoTime();
        int limit = 100;
        int windowSeconds = 60;

        for (int i = 1; i <= limit; i++) {
            RateCheckResult result = rateLimiterService.check(clientId, key, limit, windowSeconds, 1);
            assertThat(result.allowed()).as("request %d should be allowed", i).isTrue();
            assertThat(result.currentCount()).isEqualTo(i);
            assertThat(result.remaining()).isEqualTo(limit - i);
        }

        RateCheckResult blocked = rateLimiterService.check(clientId, key, limit, windowSeconds, 1);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.currentCount()).isEqualTo(limit);
        assertThat(blocked.remaining()).isEqualTo(0);
        assertThat(blocked.resetInSeconds()).isGreaterThan(0);
    }

    @Test
    void windowExpiryUsesInjectedClockWithoutSleeping() {
        String clientId = "test-client";
        String key = "usr_expiry_" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            RateCheckResult r = rateLimiterService.check(clientId, key, 5, 10, 1);
            assertThat(r.allowed()).isTrue();
        }
        RateCheckResult blocked = rateLimiterService.check(clientId, key, 5, 10, 1);
        assertThat(blocked.allowed()).isFalse();
    }

    @Test
    void tenantIsolationKeepsIndependentCounters() {
        String key = "shared_user_key";
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiterService.check("client-a", key, 3, 60, 1).allowed()).isTrue();
        }
        assertThat(rateLimiterService.check("client-a", key, 3, 60, 1).allowed()).isFalse();

        assertThat(rateLimiterService.check("client-b", key, 3, 60, 1).allowed()).isTrue();
    }

    @Test
    void costGreaterThanOneAccountsCorrectly() {
        String clientId = "test-client";
        String key = "usr_cost_" + System.nanoTime();

        RateCheckResult r1 = rateLimiterService.check(clientId, key, 10, 60, 5);
        assertThat(r1.allowed()).isTrue();
        assertThat(r1.currentCount()).isEqualTo(5);
        assertThat(r1.remaining()).isEqualTo(5);

        RateCheckResult r2 = rateLimiterService.check(clientId, key, 10, 60, 5);
        assertThat(r2.allowed()).isTrue();
        assertThat(r2.currentCount()).isEqualTo(10);
        assertThat(r2.remaining()).isEqualTo(0);

        RateCheckResult r3 = rateLimiterService.check(clientId, key, 10, 60, 1);
        assertThat(r3.allowed()).isFalse();
        assertThat(r3.currentCount()).isEqualTo(10);
    }

    @Test
    void checkBatchPipelinesMultipleCommandsInOneRoundTrip() {
        String clientId = "batch-client";
        String keyA = "usr_batch_a_" + System.nanoTime();
        String keyB = "usr_batch_b_" + System.nanoTime();

        List<RateCheckResult> results = rateLimiterService.checkBatch(clientId, List.of(
                new RateCheckCommand(keyA, 2, 60, 1),
                new RateCheckCommand(keyB, 2, 60, 1),
                new RateCheckCommand(keyA, 2, 60, 1),
                new RateCheckCommand(keyA, 2, 60, 1)
        ));

        assertThat(results).hasSize(4);
        assertThat(results.get(0).allowed()).isTrue();
        assertThat(results.get(0).currentCount()).isEqualTo(1);
        assertThat(results.get(1).allowed()).isTrue();
        assertThat(results.get(1).currentCount()).isEqualTo(1);
        assertThat(results.get(2).allowed()).isTrue();
        assertThat(results.get(2).currentCount()).isEqualTo(2);
        assertThat(results.get(3).allowed()).isFalse();
        assertThat(results.get(3).currentCount()).isEqualTo(2);
    }

    @Test
    void peekDoesNotConsumeCapacity() {
        String clientId = "peek-client";
        String key = "usr_peek_" + System.nanoTime();

        rateLimiterService.check(clientId, key, 5, 60, 1);
        rateLimiterService.check(clientId, key, 5, 60, 1);

        var peek1 = rateLimiterService.peek(clientId, key, 5, 60);
        var peek2 = rateLimiterService.peek(clientId, key, 5, 60);

        assertThat(peek1.currentCount()).isEqualTo(2);
        assertThat(peek2.currentCount()).isEqualTo(2);
    }

    @Test
    void resetClearsCounter() {
        String clientId = "reset-client";
        String key = "usr_reset_" + System.nanoTime();

        rateLimiterService.check(clientId, key, 1, 60, 1);
        assertThat(rateLimiterService.check(clientId, key, 1, 60, 1).allowed()).isFalse();

        rateLimiterService.reset(clientId, key);

        assertThat(rateLimiterService.check(clientId, key, 1, 60, 1).allowed()).isTrue();
    }
}
