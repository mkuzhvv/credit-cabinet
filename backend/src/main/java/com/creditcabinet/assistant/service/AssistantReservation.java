package com.creditcabinet.assistant.service;

import java.util.UUID;

import com.creditcabinet.assistant.model.AssistantContext;
import com.creditcabinet.common.idempotency.IdempotencyResult;
import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.identity.model.CurrentUser;

public sealed interface AssistantReservation {

    record Replay(StoredResponse response) implements AssistantReservation {
    }

    record Generation(AssistantContext input, IdempotencyResult.Claim command, UUID token, CurrentUser owner) implements AssistantReservation {
    }
}
