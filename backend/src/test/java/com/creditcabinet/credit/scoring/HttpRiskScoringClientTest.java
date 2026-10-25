package com.creditcabinet.credit.scoring;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import com.creditcabinet.credit.credithistory.CreditHistoryReportValidator;
import com.creditcabinet.credit.credithistory.DemoCreditHistoryProvider;
import com.creditcabinet.credit.credithistory.DemoCreditHistoryScenario;
import com.creditcabinet.credit.decision.CreditDecisionPolicy;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.CompanyFacts;
import com.creditcabinet.credit.decision.DecisionTestData;
import com.creditcabinet.credit.decision.SubmissionContext;
import com.creditcabinet.credit.decision.SubmissionValidator;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.service.ApplicationFormValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRiskScoringClientTest {

    private static final String MODEL = "test-rules-v4";
    private static final UUID COMPANY = UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final AtomicInteger readyCalls = new AtomicInteger();
    private final AtomicInteger assessmentCalls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private HttpRiskScoringClient client;
    private URI baseUrl;
    private ObjectNode readiness;
    private volatile JsonNode sent;
    private volatile String sentText;
    private volatile UUID receivedRequestId;
    private volatile boolean leakedCookies;
    private int readyStatus = 200;
    private int assessmentStatus = 200;
    private long readyDelay;
    private long assessmentDelay;
    private boolean dripBody;
    private String rawAssessment;
    private String responseContentType = "application/json";
    private UnaryOperator<ObjectNode> mutateAssessment = body -> body;

    @BeforeEach
    void start() throws IOException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        readiness = mapper.createObjectNode().put("status", "READY").put("modelVersion", MODEL).put("featureSchemaVersion", "4");
        readiness.set("supportedProductCodes", mapper.valueToTree(List.of("WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE")));
        server.createContext("/health/ready", exchange -> {
            readyCalls.incrementAndGet();
            inspectHeaders(exchange);
            respond(exchange, readyStatus, mapper.writeValueAsString(readiness), readyDelay, false);
        });
        server.createContext("/internal/v1/assessments", exchange -> {
            assessmentCalls.incrementAndGet();
            inspectHeaders(exchange);
            sentText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sent = mapper.readTree(sentText);
            ObjectNode response = mapper.createObjectNode().put("assessmentId", sent.path("assessmentId").asString())
                    .put("featureSchemaVersion", "4").put("modelVersion", MODEL).put("riskScore", 0);
            respond(exchange, assessmentStatus, rawAssessment == null ? mapper.writeValueAsString(mutateAssessment.apply(response)) : rawAssessment,
                    assessmentDelay, dripBody);
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = newClient(baseUrl, Duration.ofSeconds(3), MODEL);
    }

    @AfterEach
    void stop() {
        client.close();
        if (server != null) {
            server.stop(0);
        }
        executor.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE"})
    void exchangesSchemaFourForEveryProductWithoutTransportOrPersonalFields(String code) {
        ScoringRequest request = request(DecisionTestData.context(code), DemoCreditHistoryScenario.DEFAULT);
        UUID requestId = UUID.randomUUID();
        assertThat(client.assess(request, requestId).riskScore()).isZero();
        assertThat(readyCalls.get()).isEqualTo(1);
        assertThat(assessmentCalls.get()).isEqualTo(1);
        assertThat(receivedRequestId).isEqualTo(requestId);
        assertThat(leakedCookies).isFalse();
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder("assessmentId", "applicationId", "featureSchemaVersion", "features");
        JsonNode features = sent.path("features");
        assertThat(features.propertyNames()).containsExactlyInAnyOrder("productCode", "currency", "amount", "termMonths",
                "averageMonthlyInflow", "averageMonthlyOutflow", "reportingPeriod", "asOfDate", "businessAgeMonths",
                "creditHistory", "projectBudget", "trancheTermMonths", "governmentContract", "cashBalance");
        assertThat(features.path("productCode").asString()).isEqualTo(code);
        assertThat(sent.path("featureSchemaVersion").asString()).isEqualTo("4");
        assertThat(features.path("cashBalance").asString()).isEqualTo("1000000.00");
        assertThat(sentText).doesNotContain("financingPurpose", "DEMO-44-001", "sourceVersion", "receivedAt", "companyId");
        if (code.equals("GOVERNMENT_CONTRACT_LINE")) {
            assertThat(features.path("governmentContract").path("plannedEndDate").asString()).isEqualTo("2026-11-25");
        }
    }

    @Test
    void preservesNoHistoryNullsAndConfirmedZeroInflow() {
        SubmissionContext original = DecisionTestData.context("WORKING_CAPITAL");
        client.assess(request(original, DemoCreditHistoryScenario.NO_HISTORY), UUID.randomUUID());
        for (String field : Set.of("totalOutstandingDebt", "monthlyDebtPayment", "overdueDebtAmount", "maxDaysPastDueLast12Months")) {
            assertThat(sent.path("features").path("creditHistory").path(field).isNull()).isTrue();
        }
        client.assess(request(zeroInflow(original), DemoCreditHistoryScenario.DEFAULT), UUID.randomUUID());
        assertThat(sent.path("features").path("averageMonthlyInflow").asString()).isEqualTo("0.00");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            assessmentId | "00000000-0000-0000-0000-000000000001"
            featureSchemaVersion | "3"
            modelVersion | "unexpected-model"
            riskScore | null
            riskScore | -0.01
            riskScore | 0.5
            riskScore | 1.01
            riskScore | "0"
            """)
    void rejectsIncompatibleAssessment(String field, String json) {
        mutateAssessment = body -> {
            body.set(field, mapper.readTree(json));
            return body;
        };
        assertFailure("SCORING_INVALID_RESPONSE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"assessmentId", "featureSchemaVersion", "modelVersion", "riskScore"})
    void rejectsMissingResponseFields(String field) {
        mutateAssessment = body -> {
            body.remove(field);
            return body;
        };
        assertFailure("SCORING_INVALID_RESPONSE");
    }

    @Test
    void rejectsUnknownFieldsMalformedAndWrongContentType() {
        mutateAssessment = body -> body.put("unknown", true);
        assertFailure("SCORING_INVALID_RESPONSE");
        for (String invalid : List.of("{", "null", "{} {}", "{\"riskScore\":NaN}")) {
            rawAssessment = invalid;
            assertFailure("SCORING_INVALID_RESPONSE");
        }
        rawAssessment = null;
        responseContentType = "text/plain";
        assertFailure("SCORING_INVALID_RESPONSE");
    }

    @ParameterizedTest
    @CsvSource({"400,SCORING_INVALID_RESPONSE", "422,SCORING_INVALID_RESPONSE", "500,SCORING_UNAVAILABLE", "503,SCORING_UNAVAILABLE"})
    void classifiesHttpFailureWithoutRetry(int status, String code) {
        assessmentStatus = status;
        assertFailure(code);
        assertThat(readyCalls.get()).isEqualTo(1);
        assertThat(assessmentCalls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            status | "UP" | SCORING_INVALID_RESPONSE
            featureSchemaVersion | "3" | SCORING_INVALID_RESPONSE
            modelVersion | "other" | SCORING_INVALID_RESPONSE
            supportedProductCodes | null | SCORING_INVALID_RESPONSE
            supportedProductCodes | ["WORKING_CAPITAL"] | SCORING_UNAVAILABLE
            supportedProductCodes | ["WORKING_CAPITAL","WORKING_CAPITAL"] | SCORING_INVALID_RESPONSE
            """)
    void validatesReadinessBeforeSendingFeatures(String field, String json, String code) {
        readiness.set(field, mapper.readTree(json));
        assertFailure(code);
        assertThat(assessmentCalls.get()).isZero();
    }

    @Test
    void classifiesUnavailableReadinessAndConnectionFailure() {
        readyStatus = 503;
        assertFailure("SCORING_UNAVAILABLE");
        assertThat(assessmentCalls.get()).isZero();
        server.stop(0);
        server = null;
        assertFailure("SCORING_UNAVAILABLE");
    }

    @Test
    void boundsReadinessAndAssessmentTogether() {
        client.close();
        client = newClient(baseUrl, Duration.ofSeconds(1), MODEL);
        readyDelay = 600;
        assessmentDelay = 600;
        long start = System.nanoTime();
        assertFailure("SCORING_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
        assertThat(assessmentCalls.get()).isEqualTo(1);
    }

    @Test
    void boundsSlowResponseBody() {
        client.close();
        client = newClient(baseUrl, Duration.ofMillis(500), MODEL);
        dripBody = true;
        assertFailure("SCORING_TIMEOUT");
    }

    @Test
    void factoryDoesNotSendCurrentOverdueToScoring() {
        assertThatThrownBy(() -> request(DecisionTestData.context("WORKING_CAPITAL"), DemoCreditHistoryScenario.CURRENT_OVERDUE))
                .isInstanceOf(ScoringException.class);
        assertThat(assessmentCalls.get()).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named = "scoring.integration-url", matches = ".+")
    void exchangesWithCurrentPythonImplementation() {
        try (HttpRiskScoringClient actual = newClient(URI.create(System.getProperty("scoring.integration-url")), Duration.ofSeconds(10),
                System.getProperty("scoring.integration-model"))) {
            for (String code : List.of("WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE")) {
                assertThat(actual.assess(request(DecisionTestData.context(code), DemoCreditHistoryScenario.DEFAULT), UUID.randomUUID()).riskScore()).isZero();
            }
            SubmissionContext input = DecisionTestData.context("WORKING_CAPITAL");
            assertThat(actual.assess(request(input, DemoCreditHistoryScenario.NO_HISTORY), UUID.randomUUID()).riskScore()).isZero();
            assertThat(actual.assess(request(zeroInflow(input), DemoCreditHistoryScenario.DEFAULT), UUID.randomUUID()).riskScore()).isEqualByComparingTo("1");
        }
    }

    private HttpRiskScoringClient newClient(URI url, Duration budget, String model) {
        return new HttpRiskScoringClient(new ScoringProperties(url, Duration.ofMillis(300), budget, model), mapper, executor);
    }

    private ScoringRequest request(SubmissionContext context, DemoCreditHistoryScenario scenario) {
        CreditHistoryReportValidator histories = new CreditHistoryReportValidator();
        var history = new DemoCreditHistoryProvider(mapper, new DefaultResourceLoader(), Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC), histories, scenario)
                .getCreditHistory(COMPANY);
        ScoringRequestFactory factory = new ScoringRequestFactory(new SubmissionValidator(new ApplicationFormValidator()), histories, new CreditDecisionPolicy());
        return factory.create(UUID.randomUUID(), UUID.randomUUID(), COMPANY, context,
                new CompanyFacts(LocalDate.of(2019, 8, 1), true, true), history);
    }

    private SubmissionContext zeroInflow(SubmissionContext input) {
        var form = input.form();
        ApplicationForm zero = new ApplicationForm(form.amount(), form.termMonths(), java.math.BigDecimal.ZERO, form.averageMonthlyOutflow(),
                form.financingPurpose(), form.projectBudget(), form.trancheTermMonths(), form.governmentContract(), form.cashBalance());
        return new SubmissionContext(input.productCode(), input.terms(), zero, input.calculationDate(), input.reportingPeriod(), input.asOfDate());
    }

    private void assertFailure(String code) {
        assertThatThrownBy(() -> client.assess(request(DecisionTestData.context("WORKING_CAPITAL"), DemoCreditHistoryScenario.DEFAULT), UUID.randomUUID()))
                .isInstanceOfSatisfying(ScoringException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    private void inspectHeaders(HttpExchange exchange) {
        receivedRequestId = UUID.fromString(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        leakedCookies |= exchange.getRequestHeaders().containsKey("Cookie") || exchange.getRequestHeaders().containsKey("X-CSRF-TOKEN");
    }

    private void respond(HttpExchange exchange, int status, String body, long delay, boolean drip) {
        try (exchange) {
            if (delay > 0) {
                Thread.sleep(delay);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", responseContentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Request-Id", receivedRequestId.toString());
            exchange.sendResponseHeaders(status, bytes.length);
            if (drip) {
                for (int offset = 0; offset < bytes.length; offset += 10) {
                    exchange.getResponseBody().write(bytes, offset, Math.min(10, bytes.length - offset));
                    exchange.getResponseBody().flush();
                    Thread.sleep(100);
                }
            } else {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException ex) {
            return;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
