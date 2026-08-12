package com.ratelimiter.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RFC 9457 problem-detail bodies (§9). Auth failures (missing/invalid key,
 * insufficient privileges) never reach here — they're handled in the
 * security filter chain, before dispatch.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://api.example.com/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("field", fe.getField());
                    m.put("message", fe.getDefaultMessage());
                    return m;
                })
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "validation-error", firstMessage(errors, "Request failed validation"), request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("field", lastNode(v.getPropertyPath().toString()));
                    m.put("message", v.getMessage());
                    return m;
                })
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "validation-error", firstMessage(errors, "Request failed validation"), request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(CostExceedsLimitException.class)
    public ProblemDetail handleCostExceedsLimit(CostExceedsLimitException ex, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "validation-error", ex.getMessage(), request);
        problem.setProperty("errors", List.of(Map.of("field", "cost", "message", "must be less than or equal to limit")));
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedBody(WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body",
                "malformed-body", "The request body could not be parsed", request);
    }

    @ExceptionHandler(BatchTooLargeException.class)
    public ProblemDetail handleBatchTooLarge(BatchTooLargeException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Batch too large", "batch-too-large", ex.getMessage(), request);
    }

    @ExceptionHandler(ClientNotFoundException.class)
    public ProblemDetail handleClientNotFound(ClientNotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Client not found", "client-not-found", ex.getMessage(), request);
    }

    @ExceptionHandler(RateLimiterUnavailableException.class)
    public ProblemDetail handleRateLimiterUnavailable(WebRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter unavailable",
                "rate-limiter-unavailable", "The rate limiter backend is unavailable", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "validation-error", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, WebRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception, correlationId={}", correlationId, ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "internal-error",
                "An unexpected error occurred (correlationId=" + correlationId + ")", request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String typeSlug, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + typeSlug));
        problem.setInstance(URI.create(requestPath(request)));
        return problem;
    }

    private static String requestPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : description;
    }

    private static String lastNode(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }

    private static String firstMessage(List<Map<String, String>> errors, String fallback) {
        return errors.isEmpty() ? fallback : errors.get(0).get("field") + " " + errors.get(0).get("message");
    }
}
