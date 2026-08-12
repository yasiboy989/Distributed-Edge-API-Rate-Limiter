package com.ratelimiter.exception;

public class BatchTooLargeException extends RuntimeException {

    public BatchTooLargeException(String message) {
        super(message);
    }
}
