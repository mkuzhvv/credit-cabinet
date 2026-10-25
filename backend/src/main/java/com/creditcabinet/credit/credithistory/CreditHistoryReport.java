package com.creditcabinet.credit.credithistory;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreditHistoryReport(
        @JsonProperty(required = true) String schemaVersion,
        @JsonProperty(required = true) UUID companyId,
        @JsonProperty(required = true) String source,
        @JsonProperty(required = true) String sourceVersion,
        @JsonProperty(required = true) UUID reportId,
        @JsonProperty(required = true) Instant receivedAt,
        @JsonProperty(required = true) CreditHistoryProfile profile) {
}
