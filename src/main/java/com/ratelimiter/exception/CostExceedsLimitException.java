package com.ratelimiter.exception;

/**
 * Thrown when {@code cost > limit} only becomes knowable after the client's
 * default limit is substituted for an omitted request limit — the Bean
 * Validation {@code @ValidCost} constraint can't see that substitution, so it
 * only catches the case where the request supplies an explicit limit.
 */
public class CostExceedsLimitException extends RuntimeException {

    public CostExceedsLimitException() {
        super("cost must be less than or equal to limit");
    }
}
