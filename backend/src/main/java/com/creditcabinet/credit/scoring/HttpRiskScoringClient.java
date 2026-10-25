package com.creditcabinet.credit.scoring;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.creditcabinet.credit.decision.RiskEstimate;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public class HttpRiskScoringClient implements RiskScoringClient, AutoCloseable {

    private static final Set<String> PRODUCTS = Set.of("WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE");
    private final RestClient http;
    private final HttpClient transport;
    private final JsonMapper mapper;
    private final ExecutorService executor;
    private final Duration timeout;
    private final String expectedModelVersion;

    public HttpRiskScoringClient(ScoringProperties properties, JsonMapper mapper, ExecutorService executor) {
        this.transport = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(properties.timeout());
        this.http = RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(requests).build();
        this.mapper = mapper;
        this.executor = executor;
        this.timeout = properties.timeout();
        this.expectedModelVersion = properties.expectedModelVersion();
    }

    @Override
    public RiskEstimate assess(ScoringRequest request, UUID requestId) {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (request == null || request.assessmentId() == null || request.applicationId() == null || request.features() == null
                || !"4".equals(request.featureSchemaVersion()) || request.features().productCode() == null
                || !PRODUCTS.contains(request.features().productCode()) || requestId == null) {
            throw invalid("Неверная структура запроса скоринга");
        }
        Future<RiskEstimate> call = executor.submit(() -> exchange(request, requestId));
        try {
            return call.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            call.cancel(true);
            throw new ScoringException(ScoringException.Code.SCORING_TIMEOUT, "Истёк общий бюджет обращения к скорингу", ex);
        } catch (InterruptedException ex) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new ScoringException(ScoringException.Code.SCORING_UNAVAILABLE, "Обращение к скорингу прервано", ex);
        } catch (ExecutionException ex) {
            throw classify(ex.getCause());
        }
    }

    private RiskEstimate exchange(ScoringRequest request, UUID requestId) {
        ResponseEntity<byte[]> readyResponse = http.get().uri("/health/ready").accept(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", requestId.toString()).retrieve().toEntity(byte[].class);
        Readiness ready = read(readyResponse, Readiness.class);
        validateReadiness(ready, request.features().productCode());
        ResponseEntity<byte[]> response = http.post().uri("/internal/v1/assessments").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).header("X-Request-Id", requestId.toString())
                .body(mapper.writeValueAsBytes(request)).retrieve().toEntity(byte[].class);
        Assessment result = read(response, Assessment.class);
        if (!request.assessmentId().equals(result.assessmentId()) || !"4".equals(result.featureSchemaVersion())
                || !expectedModelVersion.equals(result.modelVersion())) {
            throw invalid("ID попытки, схема или версия алгоритма не совпадают с ожидаемыми");
        }
        BigDecimal risk = result.riskScore();
        if (risk == null || (risk.compareTo(BigDecimal.ZERO) != 0 && risk.compareTo(BigDecimal.ONE) != 0)) {
            throw invalid("Схема 4 допускает только бинарный результат 0 или 1");
        }
        return new RiskEstimate(4, result.modelVersion(), risk);
    }

    private void validateReadiness(Readiness ready, String productCode) {
        if (!"READY".equals(ready.status()) || !"4".equals(ready.featureSchemaVersion()) || !expectedModelVersion.equals(ready.modelVersion())) {
            throw invalid("Скоринг сообщил неподходящую готовность, схему или версию алгоритма");
        }
        List<String> codes = ready.supportedProductCodes();
        if (codes == null || codes.isEmpty() || codes.size() > 100 || new HashSet<>(codes).size() != codes.size()
                || codes.stream().anyMatch(code -> code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}"))) {
            throw invalid("Некорректный список поддерживаемых продуктов скоринга");
        }
        if (!codes.containsAll(PRODUCTS) || !codes.contains(productCode)) {
            throw new ScoringException(ScoringException.Code.SCORING_UNAVAILABLE, "Скоринг не поддерживает все продукты приложения");
        }
    }

    private <T> T read(ResponseEntity<byte[]> response, Class<T> type) {
        MediaType contentType = response.getHeaders().getContentType();
        if (response.getStatusCode().value() != 200 || contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || response.getBody() == null || response.getBody().length == 0) {
            throw invalid("Скоринг вернул неожиданный статус, формат или пустой ответ");
        }
        T value = mapper.readValue(response.getBody(), type);
        if (value == null) {
            throw invalid("Ответ скоринга не содержит объект");
        }
        return value;
    }

    private ScoringException classify(Throwable cause) {
        if (cause instanceof ScoringException failure) {
            return failure;
        }
        if (cause instanceof RestClientResponseException response) {
            ScoringException.Code code = response.getStatusCode().value() == 400 || response.getStatusCode().value() == 422
                    ? ScoringException.Code.SCORING_INVALID_RESPONSE : ScoringException.Code.SCORING_UNAVAILABLE;
            return new ScoringException(code, "Скоринг отклонил запрос или временно недоступен", cause);
        }
        if (cause instanceof ResourceAccessException) {
            for (Throwable nested = cause; nested != null; nested = nested.getCause()) {
                if (nested instanceof HttpTimeoutException || nested instanceof SocketTimeoutException) {
                    return new ScoringException(ScoringException.Code.SCORING_TIMEOUT, "Истёк срок HTTP-обращения к скорингу", cause);
                }
            }
            return new ScoringException(ScoringException.Code.SCORING_UNAVAILABLE, "Не удалось обратиться к скорингу", cause);
        }
        if (cause instanceof JacksonException || cause instanceof IllegalArgumentException) {
            return new ScoringException(ScoringException.Code.SCORING_INVALID_RESPONSE, "Некорректный ответ скоринга", cause);
        }
        return new ScoringException(ScoringException.Code.SCORING_UNAVAILABLE, "Не удалось завершить обращение к скорингу", cause);
    }

    private ScoringException invalid(String message) {
        return new ScoringException(ScoringException.Code.SCORING_INVALID_RESPONSE, message);
    }

    @Override
    public void close() {
        transport.shutdownNow();
    }

    private record Readiness(@JsonProperty(required = true) String status, @JsonProperty(required = true) String modelVersion,
            @JsonProperty(required = true) String featureSchemaVersion, @JsonProperty(required = true) List<String> supportedProductCodes) {
    }

    private record Assessment(@JsonProperty(required = true) UUID assessmentId, @JsonProperty(required = true) String featureSchemaVersion,
            @JsonProperty(required = true) String modelVersion, @JsonProperty(required = true) BigDecimal riskScore) {
    }
}
