package com.creditcabinet.credit.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.creditcabinet.catalog.api.ProductView;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.CreditDecision;
import com.creditcabinet.credit.model.ReportingPeriod;

public record ApplicationView(UUID id, long version, ApplicationStatus status, ProductView product,
        ReportingPeriod reportingPeriod, ApplicationForm form, UUID assessmentId, Instant lastSubmittedAt,
        CreditDecision decision, List<ClarificationRequest> requiredInformation, ProcessingError processingError,
        List<String> allowedActions, Instant createdAt, Instant updatedAt, LocalDate calculationDate) {

    public record ClarificationRequest(UUID clarificationId, String field, String code, String message, String observedValue) {
    }

    public record ProcessingError(String code, String message, Instant occurredAt) {
    }
}
