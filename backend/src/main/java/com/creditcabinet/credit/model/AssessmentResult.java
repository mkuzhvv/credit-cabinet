package com.creditcabinet.credit.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssessmentResult(int schemaVersion, CreditDecision decision, List<UUID> clarificationIds,
        ProcessingFailure processingError) {

    public record ProcessingFailure(String code, String message, Instant occurredAt) {
    }
}
