package com.creditcabinet.credit.processing;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("credit.processing")
public record ProcessingProperties(Duration leaseDuration, Duration pollDelay, Duration creditHistoryTimeout,
        Duration retryDelay, int maxExecutions) {

    public ProcessingProperties {
        Objects.requireNonNull(leaseDuration);
        Objects.requireNonNull(pollDelay);
        Objects.requireNonNull(creditHistoryTimeout);
        Objects.requireNonNull(retryDelay);
        if (leaseDuration.toMillis() < 1 || pollDelay.toMillis() < 1 || creditHistoryTimeout.toMillis() < 1
                || retryDelay.isNegative() || maxExecutions < 1) {
            throw new IllegalArgumentException("Сроки обработки должны быть положительными, задержка повтора неотрицательной, число запусков не меньше одного");
        }
    }
}
