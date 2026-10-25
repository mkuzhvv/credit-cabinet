package com.creditcabinet.credit.processing;

import java.time.Instant;
import java.util.UUID;

public record AssessmentClaim(UUID assessmentId, UUID applicationId, UUID token, Instant lockedUntil, int executionCount) {
}
