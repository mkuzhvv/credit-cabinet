package com.creditcabinet.credit.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

public record CreditDecision(Type type, Instant decidedAt, List<DecisionReason> reasons,
        @JsonInclude(JsonInclude.Include.NON_NULL) ApprovedTerms terms) {

    public CreditDecision {
        Objects.requireNonNull(type);
        Objects.requireNonNull(decidedAt);
        reasons = List.copyOf(reasons);
        if (type == Type.APPROVED && terms == null) {
            throw new IllegalArgumentException("Одобрение должно содержать условия");
        }
        if (type == Type.DECLINED && (terms != null || reasons.isEmpty())) {
            throw new IllegalArgumentException("Отказ должен содержать причину и не содержать одобренных условий");
        }
    }

    public enum Type {
        APPROVED, DECLINED
    }
}
