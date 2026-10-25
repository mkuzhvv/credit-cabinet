package com.creditcabinet.common.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public IdempotencyRepository(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Record reserve(IdempotencyScope scope, String hash, UUID token, Duration lease) {
        return jdbc.queryForObject("""
                INSERT INTO idempotency_record
                    (id, user_id, company_id, operation, resource_scope, idempotency_key,
                     request_hash, status, claim_token, locked_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, clock_timestamp() + ? * interval '1 millisecond')
                ON CONFLICT ON CONSTRAINT uq_idempotency_scope DO UPDATE SET id = idempotency_record.id
                RETURNING id, request_hash, status, claim_token, locked_until <= clock_timestamp() AS lease_expired,
                    response_status, response_body, response_headers
                """, this::readRecord, UUID.randomUUID(), scope.userId(), scope.companyId(), scope.operation(),
                scope.resourceScope(), scope.key(), hash, token, lease.toMillis());
    }

    void reclaim(UUID id, UUID token, Duration lease) {
        jdbc.update("""
                UPDATE idempotency_record
                SET status = 'IN_PROGRESS', claim_token = ?, locked_until = clock_timestamp() + ? * interval '1 millisecond',
                    updated_at = statement_timestamp(), expires_at = NULL
                WHERE id = ?
                """, token, lease.toMillis(), id);
    }

    boolean complete(IdempotencyResult.Claim claim, StoredResponse response) {
        return jdbc.update("""
                UPDATE idempotency_record
                SET status = 'SUCCEEDED', claim_token = NULL, locked_until = NULL,
                    response_status = ?, response_body = CAST(? AS jsonb), response_headers = CAST(? AS jsonb),
                    updated_at = statement_timestamp(), expires_at = statement_timestamp() + interval '24 hours'
                WHERE id = ? AND status = 'IN_PROGRESS' AND claim_token = ? AND locked_until > clock_timestamp()
                """, response.status(), mapper.writeValueAsString(response.body()), mapper.writeValueAsString(response.headers()),
                claim.recordId(), claim.token()) == 1;
    }

    boolean markRetryableFailure(IdempotencyResult.Claim claim) {
        return jdbc.update("""
                UPDATE idempotency_record
                SET status = 'RETRYABLE_FAILURE', claim_token = NULL, locked_until = NULL,
                    updated_at = statement_timestamp(), expires_at = statement_timestamp() + interval '24 hours'
                WHERE id = ? AND status = 'IN_PROGRESS' AND claim_token = ? AND locked_until > clock_timestamp()
                """, claim.recordId(), claim.token()) == 1;
    }

    boolean lockClaim(IdempotencyResult.Claim claim) {
        return !jdbc.query("""
                WITH owned AS MATERIALIZED (
                    SELECT id, locked_until FROM idempotency_record
                    WHERE id = ? AND status = 'IN_PROGRESS' AND claim_token = ?
                    FOR UPDATE
                )
                SELECT id FROM owned WHERE locked_until > clock_timestamp()
                """, (row, index) -> row.getObject("id", UUID.class), claim.recordId(), claim.token()).isEmpty();
    }

    int deleteExpired() {
        return jdbc.update("""
                DELETE FROM idempotency_record
                WHERE status IN ('SUCCEEDED', 'RETRYABLE_FAILURE') AND expires_at <= statement_timestamp()
                """);
    }

    private Record readRecord(ResultSet rs, int row) throws SQLException {
        String status = rs.getString("status");
        StoredResponse response = null;
        if (status.equals("SUCCEEDED")) {
            Map<String, String> headers = mapper.readValue(rs.getString("response_headers"), new TypeReference<>() { });
            response = new StoredResponse(rs.getInt("response_status"), mapper.readTree(rs.getString("response_body")), headers);
        }
        return new Record(rs.getObject("id", UUID.class), rs.getString("request_hash"), status,
                rs.getObject("claim_token", UUID.class), rs.getBoolean("lease_expired"), response);
    }

    record Record(UUID id, String requestHash, String status, @Nullable UUID claimToken,
            boolean leaseExpired, @Nullable StoredResponse response) {
    }
}
