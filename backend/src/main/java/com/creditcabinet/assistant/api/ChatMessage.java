package com.creditcabinet.assistant.api;

import java.time.Instant;
import java.util.UUID;

import com.creditcabinet.assistant.model.AssistantMessage.Role;

public record ChatMessage(UUID id, long sequence, Role role, String text, String recommendedProductCode,
        UUID replyToMessageId, Instant createdAt) {
}
