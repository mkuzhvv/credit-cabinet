package com.creditcabinet.assistant.model;

import java.util.UUID;

import com.creditcabinet.assistant.integration.AssistantGeneration.ModelMetadata;

public record GenerationMetadata(int schemaVersion, String inputSchemaVersion, String outputSchemaVersion,
        ModelMetadata model, UUID applicationId, String productCode, String fieldCode) {
}
