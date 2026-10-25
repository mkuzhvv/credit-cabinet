package com.creditcabinet.credit.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RetryApplicationRequest(
        @JsonProperty(required = true) @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion) {
}
