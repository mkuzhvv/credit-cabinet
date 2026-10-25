package com.creditcabinet.assistant.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.ReportingPeriod;
import com.creditcabinet.assistant.model.AssistantMessage.Role;
import com.creditcabinet.catalog.model.ProductTerms;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record AssistantContext(String schemaVersion, UUID conversationId, String message,
        List<HistoryMessage> history, Context context) {

    public record HistoryMessage(Role role, String text) {
    }

    public record Context(List<CatalogProduct> catalog, List<FieldDefinition> fieldDefinitions,
            String selectedProductCode, String currentFieldCode, ApplicationContext application) {
    }

    public record CatalogProduct(String code, String name, String purpose, String termsVersion, List<String> conditions,
            String financingType, int financialPeriodMonths, @JsonIgnore ProductTerms terms) {
    }

    public record FieldDefinition(String code, String label, String description) {
    }

    public record FieldValue(String fieldCode, String value) {
    }

    public record ApplicationContext(UUID applicationId, ApplicationStatus status, String productCode,
            List<FieldValue> fieldValues, LocalDate calculationDate, ReportingPeriod reportingPeriod) {
    }
}
