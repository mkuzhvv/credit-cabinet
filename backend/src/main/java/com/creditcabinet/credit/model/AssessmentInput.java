package com.creditcabinet.credit.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.creditcabinet.catalog.model.ProductTerms;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.InflowConfirmation;
import com.creditcabinet.credit.decision.SubmissionContext;

public record AssessmentInput(int schemaVersion, ProductSnapshot product, ApplicationForm form,
        LocalDate calculationDate, ReportingPeriod reportingPeriod, LocalDate asOfDate,
        CompanySnapshot company, List<InflowConfirmation> confirmations) {

    public AssessmentInput {
        confirmations = List.copyOf(confirmations);
    }

    public SubmissionContext submissionContext() {
        return new SubmissionContext(product.code(), product.terms(), form, calculationDate, reportingPeriod, asOfDate);
    }

    public record ProductSnapshot(String code, String name, String description, ProductTerms terms) {
    }

    public record CompanySnapshot(UUID id, String legalForm, String countryCode, LocalDate registrationDate,
            boolean hasSettlementAccount, boolean hasRegularInflowLast3Months) {
    }
}
