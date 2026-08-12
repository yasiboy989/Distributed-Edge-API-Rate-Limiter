package com.ratelimiter.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateClientRequest(
        @NotBlank @Size(max = 255) String clientName,
        @NotNull @Min(1) @Max(1_000_000) Integer defaultLimit,
        @NotNull @Min(1) @Max(86_400) Integer defaultWindowSeconds) {
}
