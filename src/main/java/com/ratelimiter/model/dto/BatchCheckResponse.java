package com.ratelimiter.model.dto;

import java.util.List;

public record BatchCheckResponse(List<RateCheckResponse> results, boolean allAllowed) {
}
