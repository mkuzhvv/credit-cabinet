package com.creditcabinet.credit.api;

import com.creditcabinet.credit.model.ApplicationForm;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SaveFormRequest(
        @JsonProperty(required = true) @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion,
        @JsonProperty(required = true) @NotNull @Valid ApplicationForm form) {
}
