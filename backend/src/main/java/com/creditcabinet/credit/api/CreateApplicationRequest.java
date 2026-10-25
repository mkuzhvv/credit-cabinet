package com.creditcabinet.credit.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateApplicationRequest(
        @JsonProperty(required = true) @NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String productCode) {
}
