package com.creditcabinet.credit.api;

import java.time.Instant;
import java.util.UUID;

import com.creditcabinet.credit.model.ApplicationStatus;

public record ProcessingAccepted(UUID applicationId, UUID assessmentId, ApplicationStatus status, long version,
        Instant acceptedAt, String statusUrl, int pollAfterSeconds) {
}
