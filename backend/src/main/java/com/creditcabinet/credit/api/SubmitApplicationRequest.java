package com.creditcabinet.credit.api;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.creditcabinet.credit.model.CreditClarification.ResponseType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SubmitApplicationRequest {

    @JsonProperty
    @NotNull @Min(0) @Max(9007199254740991L)
    private final Long expectedVersion;
    @JsonProperty
    @NotBlank @Size(max = 64)
    private final String expectedProductTermsVersion;
    @JsonProperty
    @Size(max = 30)
    private List<@NotNull @Valid ClarificationResponse> clarificationResponses = List.of();

    @JsonCreator
    public SubmitApplicationRequest(@JsonProperty(value = "expectedVersion", required = true) Long expectedVersion,
            @JsonProperty(value = "expectedProductTermsVersion", required = true) String expectedProductTermsVersion) {
        this.expectedVersion = expectedVersion;
        this.expectedProductTermsVersion = expectedProductTermsVersion;
    }

    @JsonSetter(value = "clarificationResponses", nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    public void setClarificationResponses(List<ClarificationResponse> responses) {
        clarificationResponses = List.copyOf(responses);
    }

    public Long expectedVersion() {
        return expectedVersion;
    }

    public String expectedProductTermsVersion() {
        return expectedProductTermsVersion;
    }

    public List<ClarificationResponse> clarificationResponses() {
        return clarificationResponses;
    }

    public record ClarificationResponse(
            @JsonProperty(required = true) @NotNull UUID clarificationId,
            @JsonProperty(required = true) @NotNull ResponseType response,
            @Size(min = 1, max = 1000) @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*") String comment) {
    }
}
