package com.creditcabinet.credit.decision;

import java.math.BigDecimal;
import java.util.Objects;

public record PolicyParameters(String policyVersion, int featureSchemaVersion, String expectedModelVersion, BigDecimal riskCutoff) {

    public PolicyParameters {
        Objects.requireNonNull(policyVersion);
        Objects.requireNonNull(expectedModelVersion);
        Objects.requireNonNull(riskCutoff);
        if (policyVersion.isBlank() || expectedModelVersion.isBlank()) {
            throw new IllegalArgumentException("Версии политики и модели обязательны");
        }
        if (featureSchemaVersion != 4) {
            throw new IllegalArgumentException("Поддерживается схема признаков 4");
        }
        if (riskCutoff.compareTo(new BigDecimal("0.50")) != 0) {
            throw new IllegalArgumentException("Формальная политика схемы 4 использует порог 0.50");
        }
    }
}
