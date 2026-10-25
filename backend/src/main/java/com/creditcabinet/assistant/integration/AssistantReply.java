package com.creditcabinet.assistant.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssistantReply(@JsonProperty(required = true) String answer,
        @JsonProperty(required = true) String recommendedProductCode) {
}
