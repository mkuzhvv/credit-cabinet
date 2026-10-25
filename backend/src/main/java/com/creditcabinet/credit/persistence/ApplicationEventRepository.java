package com.creditcabinet.credit.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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
}
