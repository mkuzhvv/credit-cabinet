package com.creditcabinet.assistant.service;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("assistant")
public record AssistantProperties(Duration timeout, Duration leaseDuration) {

    public AssistantProperties {
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(leaseDuration);
        if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || leaseDuration.compareTo(timeout.plusSeconds(5)) <= 0) {
            throw new IllegalArgumentException("Бюджет помощника должен быть в пределах 60 секунд, срок захвата — больше бюджета с запасом 5 секунд");
        }
    }
}
