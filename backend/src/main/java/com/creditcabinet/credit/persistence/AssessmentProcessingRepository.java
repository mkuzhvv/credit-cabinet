package com.creditcabinet.credit.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.creditcabinet.credit.credithistory.CreditHistoryReport;
import com.creditcabinet.credit.decision.RiskEstimate;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.AssessmentResult;
import com.creditcabinet.credit.processing.AssessmentClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AssessmentProcessingRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public AssessmentProcessingRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<CreditHistoryReport> storeHistory(AssessmentClaim claim, CreditHistoryReport report) {
        return jdbc.query("""
                UPDATE credit_assessment SET credit_history_snapshot = COALESCE(credit_history_snapshot, CAST(? AS jsonb))
                WHERE id = ? AND application_id = ? AND processing_status = 'RUNNING' AND lock_token = ?
                    AND locked_until > clock_timestamp()
                RETURNING credit_history_snapshot
                """, (row, index) -> mapper.readValue(row.getString(1), CreditHistoryReport.class),
                mapper.writeValueAsString(report), claim.assessmentId(), claim.applicationId(), claim.token()).stream().findFirst();
    }

    public boolean finish(AssessmentClaim claim, ApplicationStatus outcome, AssessmentResult result,
            RiskEstimate risk, Instant now, boolean expired) {
        String error = result.processingError() == null ? null : result.processingError().code();
        return jdbc.update("""
                UPDATE credit_assessment
                SET processing_status = 'FINISHED', outcome = ?, result_payload = CAST(? AS jsonb),
                    risk_score = ?, actual_model_version = ?, finished_at = ?, last_error_code = ?,
                    lock_token = NULL, locked_until = NULL
                WHERE id = ? AND application_id = ? AND processing_status = 'RUNNING' AND lock_token = ?
                    AND locked_until %s clock_timestamp()
                """.formatted(expired ? "<=" : ">"), outcome.name(), mapper.writeValueAsString(result),
                risk == null ? null : risk.riskScore(), risk == null ? null : risk.modelVersion(), Timestamp.from(now), error,
                claim.assessmentId(), claim.applicationId(), claim.token()) == 1;
    }
}
