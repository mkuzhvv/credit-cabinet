package com.creditcabinet.common.idempotency;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

@Service
public class IdempotencyService {

    private final IdempotencyRepository records;
    private final RequestHasher hasher;
    private final JsonMapper mapper;

    public IdempotencyService(IdempotencyRepository records, RequestHasher hasher, JsonMapper mapper) {
        this.records = records;
        this.hasher = hasher;
        this.mapper = mapper;
    }

    @Transactional
    public IdempotencyResult begin(IdempotencyScope scope, @Nullable Object request, Duration lease) {
        Assert.isTrue(lease.toMillis() > 0, "Lease must be at least one millisecond");
        String hash = hasher.hash(request);
        UUID token = UUID.randomUUID();
        var record = records.reserve(scope, hash, token, lease);
        if (!record.requestHash().equals(hash)) {
            throw IdempotencyConflictException.keyReused();
        }
        if (record.status().equals("SUCCEEDED")) {
            return new IdempotencyResult.Replay(Objects.requireNonNull(record.response()));
        }
        if (token.equals(record.claimToken())) {
            return new IdempotencyResult.Claim(record.id(), token);
        }
        if (record.status().equals("IN_PROGRESS") && !record.leaseExpired()) {
            throw IdempotencyConflictException.operationInProgress();
        }
        records.reclaim(record.id(), token, lease);
        return new IdempotencyResult.Claim(record.id(), token);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResponse complete(IdempotencyResult.Claim claim, int status, Object body, HttpHeaders headers) {
        StoredResponse response = new StoredResponse(status, mapper.valueToTree(body), headers.toSingleValueMap());
        if (!records.complete(claim, response)) {
            throw IdempotencyConflictException.claimLost();
        }
        return response;
    }

    @Transactional
    public boolean markRetryableFailure(IdempotencyResult.Claim claim) {
        return records.markRetryableFailure(claim);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockClaim(IdempotencyResult.Claim claim) {
        return records.lockClaim(claim);
    }

    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    public int deleteExpired() {
        return records.deleteExpired();
    }
}
