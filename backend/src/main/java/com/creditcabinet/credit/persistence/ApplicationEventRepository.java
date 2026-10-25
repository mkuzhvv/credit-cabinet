package com.creditcabinet.credit.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import com.creditcabinet.credit.model.ApplicationStatus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationEventRepository {

    private final JdbcTemplate jdbc;

    public ApplicationEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordCreated(UUID applicationId, long version, UUID userId, UUID requestId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO application_event
                    (id, application_id, application_version, to_status, actor_user_id, request_id, created_at)
                VALUES (?, ?, ?, 'DRAFT', ?, ?, ?)
                """, UUID.randomUUID(), applicationId, version, userId, requestId, Timestamp.from(createdAt));
    }

    public void recordTransition(UUID applicationId, long version, UUID assessmentId, ApplicationStatus from,
            ApplicationStatus to, String reason, UUID userId, UUID requestId, Instant now) {
        jdbc.update("""
                INSERT INTO application_event
                    (id, application_id, application_version, assessment_id, from_status, to_status,
                     reason_code, actor_user_id, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), applicationId, version, assessmentId, from.name(), to.name(),
                reason, userId, requestId, Timestamp.from(now));
    }

    public Optional<UUID> findSubmissionRequestId(UUID assessmentId) {
        return jdbc.query("""
                SELECT request_id FROM application_event
                WHERE assessment_id = ? AND to_status = 'CHECKING'
                ORDER BY application_version LIMIT 1
                """, (row, index) -> row.getObject("request_id", UUID.class), assessmentId).stream().findFirst();
    }
}
