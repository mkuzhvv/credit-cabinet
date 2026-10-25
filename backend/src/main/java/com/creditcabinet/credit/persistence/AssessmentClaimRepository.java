package com.creditcabinet.credit.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.creditcabinet.credit.processing.AssessmentClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AssessmentClaimRepository {

    private final JdbcTemplate jdbc;

    public AssessmentClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AssessmentClaim> claimNext(Duration lease) {
        return jdbc.query("""
                WITH next_attempt AS (
                    SELECT id FROM credit_assessment
                    WHERE processing_status = 'READY' AND next_run_at <= statement_timestamp()
                    ORDER BY next_run_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE credit_assessment a
                SET processing_status = 'RUNNING', lock_token = ?,
                    locked_until = clock_timestamp() + ? * interval '1 millisecond',
                    execution_count = a.execution_count + 1
                FROM next_attempt n
                WHERE a.id = n.id
                RETURNING a.id, a.application_id, a.lock_token, a.locked_until, a.execution_count
                """, this::readClaim, UUID.randomUUID(), lease.toMillis()).stream().findFirst();
    }

    public Optional<AssessmentClaim> lockOwned(AssessmentClaim claim) {
        return lock(claim, false);
    }

    public Optional<AssessmentClaim> lockExpired(AssessmentClaim claim) {
        return lock(claim, true);
    }

    private Optional<AssessmentClaim> lock(AssessmentClaim claim, boolean expired) {
        return jdbc.query("""
                WITH owned AS MATERIALIZED (
                    SELECT id, application_id, lock_token, locked_until, execution_count
                    FROM credit_assessment
                    WHERE id = ? AND application_id = ? AND processing_status = 'RUNNING' AND lock_token = ?
                    FOR UPDATE
                )
                SELECT id, application_id, lock_token, locked_until, execution_count
                FROM owned WHERE locked_until %s clock_timestamp()
                """.formatted(expired ? "<=" : ">"), this::readClaim, claim.assessmentId(), claim.applicationId(), claim.token()).stream().findFirst();
    }

    public Optional<AssessmentClaim> findExpiredCandidate() {
        return jdbc.query("""
                SELECT c.id, c.application_id, c.lock_token, c.locked_until, c.execution_count
                FROM credit_application a JOIN credit_assessment c ON c.id = a.current_assessment_id AND c.application_id = a.id
                WHERE a.status = 'CHECKING' AND c.processing_status = 'RUNNING' AND c.locked_until <= clock_timestamp()
                ORDER BY c.locked_until, c.id LIMIT 1
                FOR UPDATE OF a SKIP LOCKED
                """, this::readClaim).stream().findFirst();
    }

    public boolean requeueExpired(AssessmentClaim claim, Duration delay, int maxExecutions) {
        return jdbc.update("""
                UPDATE credit_assessment
                SET processing_status = 'READY', lock_token = NULL, locked_until = NULL,
                    next_run_at = clock_timestamp() + ? * interval '1 millisecond', last_error_code = 'PROCESSING_INTERRUPTED'
                WHERE id = ? AND application_id = ? AND processing_status = 'RUNNING' AND lock_token = ?
                    AND locked_until <= clock_timestamp() AND execution_count < ?
                """, delay.toMillis(), claim.assessmentId(), claim.applicationId(), claim.token(), maxExecutions) == 1;
    }

    private AssessmentClaim readClaim(ResultSet row, int index) throws SQLException {
        return new AssessmentClaim(row.getObject("id", UUID.class), row.getObject("application_id", UUID.class),
                row.getObject("lock_token", UUID.class), row.getTimestamp("locked_until").toInstant(), row.getInt("execution_count"));
    }
}
