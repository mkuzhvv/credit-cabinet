package com.creditcabinet.assistant.api;

import java.util.UUID;

public record MessageExchange(UUID conversationId, ChatMessage userMessage, ChatMessage assistantMessage) {
}
