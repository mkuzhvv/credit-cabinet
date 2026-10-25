package com.creditcabinet.credit.scoring;

import java.util.UUID;

public record ScoringRequest(UUID assessmentId, UUID applicationId, String featureSchemaVersion, ScoringFeatures features) {
}
