package com.creditcabinet.credit.decision;

import java.math.BigDecimal;

public record RiskEstimate(int featureSchemaVersion, String modelVersion, BigDecimal riskScore) {
}
