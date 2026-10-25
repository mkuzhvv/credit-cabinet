package com.creditcabinet.assistant.api;

import java.time.Instant;
import java.util.UUID;

public record ConversationView(UUID id, Instant createdAt, Instant updatedAt) {
}
