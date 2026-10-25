package com.creditcabinet.credit.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.creditcabinet.credit.decision.CreditDecisionPolicy.ClarificationRequirement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "credit_clarification")
public class CreditClarification {

    @Id
    private UUID id;
    @Column(name = "application_id", nullable = false)
    private UUID applicationId;
    @Column(name = "source_assessment_id", nullable = false)
    private UUID sourceAssessmentId;
    @Column(name = "rule_code", nullable = false, length = 80)
    private String ruleCode;
    @Column(name = "field_code", nullable = false, length = 120)
    private String fieldCode;
    @Column(nullable = false, length = 1000)
    private String message;
    @Column(name = "observed_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal observedValue;
    @Column(name = "reporting_period_start", nullable = false)
    private LocalDate reportingPeriodStart;
    @Column(name = "reporting_period_end", nullable = false)
    private LocalDate reportingPeriodEnd;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "response_type", length = 32)
    private ResponseType responseType;
    @Column(name = "response_comment", length = 1000)
    private String responseComment;
    @Column(name = "answered_value", precision = 14, scale = 2)
    private BigDecimal answeredValue;
    @Column(name = "answered_by_user_id")
    private UUID answeredByUserId;
    @Column(name = "answered_at")
    private Instant answeredAt;
    @Column(name = "response_assessment_id")
    private UUID responseAssessmentId;

    protected CreditClarification() {
    }

    public CreditClarification(UUID applicationId, UUID assessmentId, ClarificationRequirement requirement, Instant now) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.sourceAssessmentId = assessmentId;
        this.ruleCode = requirement.code();
        this.fieldCode = requirement.field();
        this.message = requirement.message();
        this.observedValue = requirement.observedValue();
        this.reportingPeriodStart = requirement.reportingPeriod().startDate();
        this.reportingPeriodEnd = requirement.reportingPeriod().endDate();
        this.createdAt = now;
    }

    public void answer(ResponseType response, String comment, BigDecimal value, UUID userId, UUID assessmentId, Instant now) {
        responseType = response;
        responseComment = comment;
        answeredValue = value;
        answeredByUserId = userId;
        responseAssessmentId = assessmentId;
        answeredAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getFieldCode() {
        return fieldCode;
    }

    public String getMessage() {
        return message;
    }

    public BigDecimal getObservedValue() {
        return observedValue;
    }

    public LocalDate getReportingPeriodStart() {
        return reportingPeriodStart;
    }

    public LocalDate getReportingPeriodEnd() {
        return reportingPeriodEnd;
    }

    public enum ResponseType {
        CONFIRM_VALUE, VALUE_CORRECTED
    }
}
