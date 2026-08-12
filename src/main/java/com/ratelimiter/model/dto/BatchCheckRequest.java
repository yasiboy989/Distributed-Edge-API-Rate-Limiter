package com.ratelimiter.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchCheckRequest(@NotEmpty @Valid List<RateCheckRequest> requests) {
}
