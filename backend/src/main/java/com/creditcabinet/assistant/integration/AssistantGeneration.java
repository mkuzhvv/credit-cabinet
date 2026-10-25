package com.creditcabinet.assistant.integration;

import java.util.Map;

public record AssistantGeneration(AssistantReply reply, ModelMetadata metadata) {

    public record ModelMetadata(String model, String modelDigest, String promptVersion, Map<String, String> parameters) {
        public ModelMetadata {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }
}
