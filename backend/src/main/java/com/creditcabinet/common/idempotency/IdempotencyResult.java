package com.creditcabinet.common.idempotency;

import java.util.UUID;

public sealed interface IdempotencyResult {

    record Claim(UUID recordId, UUID token) implements IdempotencyResult {
    }

    record Replay(StoredResponse response) implements IdempotencyResult {
    }
}
