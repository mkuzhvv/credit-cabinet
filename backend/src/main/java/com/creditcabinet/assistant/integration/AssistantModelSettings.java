package com.creditcabinet.assistant.integration;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantModelSettings(
        String model,
        @JsonProperty("model_digest") String modelDigest,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("input_schema_version") String inputSchemaVersion,
        @JsonProperty("output_schema_version") String outputSchemaVersion,
        @JsonProperty("num_ctx") int numCtx,
        @JsonProperty("num_predict") int numPredict,
        double temperature,
        int seed,
        boolean think,
        @JsonProperty("keep_alive") String keepAlive,
        @JsonProperty("framing_reserve_tokens") int framingReserveTokens,
        @JsonProperty("presence_penalty") double presencePenalty,
        @JsonProperty("repeat_penalty") double repeatPenalty) {

    public static AssistantModelSettings load(JsonMapper mapper) throws IOException {
        var settings = mapper.readValue(new ClassPathResource("assistant/config.json").getContentAsByteArray(), AssistantModelSettings.class);
        if (!"2".equals(settings.inputSchemaVersion()) || !"1".equals(settings.outputSchemaVersion())
                || settings.think() || settings.numCtx() <= settings.numPredict() + settings.framingReserveTokens()) {
            throw new IllegalStateException("Конфигурация помощника несовместима с контрактом");
        }
        return settings;
    }
}
