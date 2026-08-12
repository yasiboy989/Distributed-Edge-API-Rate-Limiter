package com.ratelimiter.model.dto;

import com.ratelimiter.validation.ValidCost;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@ValidCost
public record RateCheckRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9:_.\\-]{1,128}$") String key,
        @Min(1) @Max(1_000_000) Integer limit,
        @Min(1) @Max(86_400) Integer windowSeconds,
        @Min(1) @Max(1000) Integer cost) {

    public RateCheckRequest {
        if (cost == null) {
            cost = 1;
        }
    }
}
