package com.creditcabinet.assistant.integration;

import java.util.UUID;

import com.creditcabinet.assistant.model.AssistantContext;

public interface AssistantGateway {

    AssistantGeneration generate(AssistantContext context, UUID requestId);
}
