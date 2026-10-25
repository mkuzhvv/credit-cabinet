package com.creditcabinet.assistant.api;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @JsonProperty(required = true) @NotBlank @Size(max = 2000) String text,
        @JsonProperty(required = true) @NotNull @Valid MessageContext context) {

    public record MessageContext(
            @JsonProperty(required = true) UUID applicationId,
            @JsonProperty(required = true) @Size(min = 2, max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String productCode,
            @JsonProperty(required = true) @Size(min = 1, max = 120)
            @Pattern(regexp = "[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*") String fieldCode) {
    }
}
