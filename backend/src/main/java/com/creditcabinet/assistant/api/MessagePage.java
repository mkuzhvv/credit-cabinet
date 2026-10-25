package com.creditcabinet.assistant.api;

import java.util.List;

public record MessagePage(List<ChatMessage> items, long lastSequence, boolean hasMore) {
}
