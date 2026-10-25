package com.creditcabinet.assistant.integration;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("assistant.ollama")
public record OllamaProperties(URI baseUrl, Duration connectTimeout) {

    public OllamaProperties {
        if (baseUrl == null || !"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())
                || connectTimeout == null || connectTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("Нужны HTTP-адрес Ollama и положительный таймаут соединения");
        }
    }
}
