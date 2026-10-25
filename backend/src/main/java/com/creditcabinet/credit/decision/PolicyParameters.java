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
        if (featureSchemaVersion != 3) {
            throw new IllegalArgumentException("Поддерживается схема признаков 3");
        }
        if (riskCutoff.signum() < 0 || riskCutoff.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Порог риска должен быть в диапазоне [0, 1]");
        }
    }
}
