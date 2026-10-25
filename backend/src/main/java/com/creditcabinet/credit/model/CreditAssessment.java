package com.creditcabinet.credit.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditcabinet.credit.credithistory.CreditHistoryReport;
import com.creditcabinet.credit.decision.PolicyParameters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "credit_assessment")
public class CreditAssessment {

    @Id
    private UUID id;
    @Column(name = "application_id", nullable = false)
    private UUID applicationId;
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;
    @Column(name = "previous_assessment_id")
    private UUID previousAssessmentId;
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    private ProcessingStatus processingStatus;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ApplicationStatus outcome;
    @Column(name = "snapshot_schema_version", nullable = false)
    private int snapshotSchemaVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private AssessmentInput inputSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credit_history_snapshot", columnDefinition = "jsonb")
    private CreditHistoryReport creditHistorySnapshot;
    @Column(name = "policy_version", nullable = false, length = 64)
    private String policyVersion;
    @Column(name = "feature_schema_version", nullable = false, length = 64)
    private String featureSchemaVersion;
    @Column(name = "expected_model_version", nullable = false, length = 128)
    private String expectedModelVersion;
    @Column(name = "risk_cutoff", nullable = false)
    private BigDecimal riskCutoff;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_payload", columnDefinition = "jsonb")
    private AssessmentResult resultPayload;
    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected CreditAssessment() {
    }

    public CreditAssessment(UUID applicationId, int attemptNo, UUID previousAssessmentId,
            AssessmentInput input, PolicyParameters parameters, Instant now) {
        this(applicationId, attemptNo, previousAssessmentId, input, parameters.policyVersion(),
                Integer.toString(parameters.featureSchemaVersion()), parameters.expectedModelVersion(), parameters.riskCutoff(), now);
    }

    private CreditAssessment(UUID applicationId, int attemptNo, UUID previousAssessmentId, AssessmentInput input,
            String policyVersion, String featureSchemaVersion, String expectedModelVersion, BigDecimal riskCutoff, Instant now) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.attemptNo = attemptNo;
        this.previousAssessmentId = previousAssessmentId;
        this.processingStatus = ProcessingStatus.READY;
        this.snapshotSchemaVersion = input.schemaVersion();
        this.inputSnapshot = input;
        this.policyVersion = policyVersion;
        this.featureSchemaVersion = featureSchemaVersion;
        this.expectedModelVersion = expectedModelVersion;
        this.riskCutoff = riskCutoff;
        this.nextRunAt = now;
        this.createdAt = now;
    }

    public CreditAssessment retry(Instant now) {
        CreditAssessment retry = new CreditAssessment(applicationId, attemptNo + 1, id, inputSnapshot,
                policyVersion, featureSchemaVersion, expectedModelVersion, riskCutoff, now);
        retry.creditHistorySnapshot = creditHistorySnapshot;
        return retry;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public ApplicationStatus getOutcome() {
        return outcome;
    }

    public AssessmentInput getInputSnapshot() {
        return inputSnapshot;
    }

    public AssessmentResult getResultPayload() {
        return resultPayload;
    }

    public CreditHistoryReport getCreditHistorySnapshot() {
        return creditHistorySnapshot;
    }

    public PolicyParameters getPolicyParameters() {
        return new PolicyParameters(policyVersion, Integer.parseInt(featureSchemaVersion), expectedModelVersion, riskCutoff);
    }

    public enum ProcessingStatus {
        READY, RUNNING, FINISHED
    }
}
