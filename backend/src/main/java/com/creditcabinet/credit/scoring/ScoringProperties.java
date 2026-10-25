package com.creditcabinet.credit.scoring;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("scoring")
public record ScoringProperties(URI baseUrl, Duration connectTimeout, Duration timeout, String expectedModelVersion) {

    public ScoringProperties {
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(connectTimeout);
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(expectedModelVersion);
        if ((!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("Адрес скоринга должен быть HTTP или HTTPS URL");
        }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0
                || connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.compareTo(timeout) > 0) {
            throw new IllegalArgumentException("Бюджет скоринга должен быть в пределах 10 секунд, connect-timeout не больше бюджета");
        }
        if (expectedModelVersion.isBlank() || expectedModelVersion.length() > 128) {
            throw new IllegalArgumentException("Закреплённая версия алгоритма обязательна и не должна превышать 128 символов");
        }
    }
}
