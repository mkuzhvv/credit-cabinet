package com.creditcabinet.credit.decision;

import java.time.LocalDate;
import java.util.Objects;

import com.creditcabinet.catalog.model.ProductTerms;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.model.ReportingPeriod;

public record SubmissionContext(String productCode, ProductTerms terms, ApplicationForm form,
        LocalDate calculationDate, ReportingPeriod reportingPeriod, LocalDate asOfDate) {

    public SubmissionContext {
        Objects.requireNonNull(productCode);
        Objects.requireNonNull(terms);
        Objects.requireNonNull(form);
        Objects.requireNonNull(calculationDate);
        Objects.requireNonNull(reportingPeriod);
        Objects.requireNonNull(asOfDate);
        if (asOfDate.isBefore(calculationDate)) {
            throw new IllegalArgumentException("Дата рассмотрения не может предшествовать созданию черновика");
        }
    }
}
