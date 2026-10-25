package com.creditcabinet.credit.processing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.creditcabinet.credit.api.CreateApplicationRequest;
import com.creditcabinet.credit.api.RetryApplicationRequest;
import com.creditcabinet.credit.api.SaveFormRequest;
import com.creditcabinet.credit.api.SubmitApplicationRequest;
import com.creditcabinet.credit.api.SubmitApplicationRequest.ClarificationResponse;
import com.creditcabinet.credit.credithistory.CreditHistoryException;
import com.creditcabinet.credit.credithistory.CreditHistoryProvider;
import com.creditcabinet.credit.credithistory.CreditHistoryReport;
import com.creditcabinet.credit.credithistory.CreditHistoryReportValidator;
import com.creditcabinet.credit.credithistory.CreditHistoryService;
import com.creditcabinet.credit.credithistory.DemoCreditHistoryProvider;
import com.creditcabinet.credit.credithistory.DemoCreditHistoryScenario;
import com.creditcabinet.credit.decision.DecisionTestData;
import com.creditcabinet.credit.decision.RiskEstimate;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.CreditClarification.ResponseType;
import com.creditcabinet.credit.scoring.HttpRiskScoringClient;
import com.creditcabinet.credit.scoring.RiskScoringClient;
import com.creditcabinet.credit.scoring.ScoringException;
import com.creditcabinet.credit.scoring.ScoringProperties;
import com.creditcabinet.credit.scoring.ScoringRequest;
import com.creditcabinet.credit.service.ApplicationService;
import com.creditcabinet.credit.service.AssessmentStateService;
import com.creditcabinet.credit.service.ProcessApplicationService;
import com.creditcabinet.credit.service.RetryApplicationService;
import com.creditcabinet.credit.service.SubmitApplicationService;
import com.creditcabinet.identity.model.CurrentUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.username=credit_cabinet", "spring.datasource.password=",
        "credit.processing.enabled=false", "credit.processing.retry-delay=0s"
})
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
@EnabledIfSystemProperty(named = "applications.db-test", matches = "true")
@Timeout(30)
class ProcessingPipelineTest {

    private static final String SCHEMA = "stage11_pipeline_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final CurrentUser USER = new CurrentUser(UUID.fromString("b827db91-df42-4d75-b5e0-8f0a7a512ee1"),
            UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef"));

    @Autowired private ApplicationService applications;
    @Autowired private SubmitApplicationService submissions;
    @Autowired private RetryApplicationService retries;
    @Autowired private AssessmentClaimService claims;
    @Autowired private AssessmentRecoveryService recovery;
    @Autowired private AssessmentStateService state;
    @Autowired private ProcessApplicationService processing;
    @Autowired private ControlledHistory source;
    @Autowired private ControlledScoring scoring;
    @Autowired private ScoringProperties scoringProperties;
    @Autowired private CreditHistoryReportValidator validator;
    @Autowired private ResourceLoader resources;
    @Autowired private JsonMapper mapper;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired @Qualifier("creditHistoryExecutor") private ExecutorService historyExecutor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:5432/credit_cabinet?currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterEach
    void clean() {
        source.reset();
        scoring.reset();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM application_event");
            jdbc.update("DELETE FROM credit_clarification");
            jdbc.update("UPDATE credit_application SET current_assessment_id = NULL, last_submitted_at = NULL, status = 'DRAFT'");
            jdbc.update("DELETE FROM credit_assessment");
            jdbc.update("DELETE FROM credit_application");
            jdbc.update("DELETE FROM idempotency_record");
            jdbc.update("UPDATE company SET registration_date = '2019-08-01', has_settlement_account = true, has_regular_inflow_last_3_months = true WHERE id = ?", USER.companyId());
        });
    }

    @AfterAll
    static void dropSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE"})
    void approvesEveryProductAndDoesNotFinishTwice(String code) {
        UUID application = submit(code, DecisionTestData.context(code).form());
        AssessmentClaim claim = claims.claimNext().orElseThrow();
        processing.process(claim);
        var card = asUser(() -> applications.getApplication(application));
        assertThat(card.status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(card.decision().terms().amount()).isEqualByComparingTo(card.form().amount());
        assertThat(card.decision().terms().annualRatePercent()).isEqualByComparingTo(card.product().terms().annualRatePercent());
        assertThat(card.allowedActions()).isEmpty();
        assertThat(source.calls.get()).isEqualTo(1);
        assertThat(scoring.calls.get()).isEqualTo(1);
        assertThat(scoring.last.features().cashBalance()).isEqualByComparingTo("1000000.00");
        assertThat(jdbc.queryForObject("SELECT processing_status FROM credit_assessment WHERE id = ?", String.class, claim.assessmentId())).isEqualTo("FINISHED");
        processing.process(claim);
        assertThat(scoring.calls.get()).isEqualTo(1);
        assertThat(finishEvents(application)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"LONG_TERM,BUSINESS_TOO_YOUNG", "OVERDRAFT,SETTLEMENT_ACCOUNT_REQUIRED", "OVERDRAFT,REGULAR_INFLOW_REQUIRED"})
    void declinesCompanyBeforeExternalCalls(String code, String reason) {
        if (reason.equals("BUSINESS_TOO_YOUNG")) {
            jdbc.update("UPDATE company SET registration_date = '2026-09-01' WHERE id = ?", USER.companyId());
        } else if (reason.equals("SETTLEMENT_ACCOUNT_REQUIRED")) {
            jdbc.update("UPDATE company SET has_settlement_account = false WHERE id = ?", USER.companyId());
        } else {
            jdbc.update("UPDATE company SET has_regular_inflow_last_3_months = false WHERE id = ?", USER.companyId());
        }
        UUID application = submit(code, DecisionTestData.context(code).form());
        processing.process(claims.claimNext().orElseThrow());
        assertReason(application, reason);
        assertThat(source.calls.get()).isZero();
        assertThat(scoring.calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT credit_history_snapshot IS NULL AND risk_score IS NULL FROM credit_assessment WHERE application_id = ?", Boolean.class, application)).isTrue();
    }

    @Test
    void checksCurrentOverdueBeforeZeroInflowAndSupportsNoHistory() {
        source.action = id -> report(DemoCreditHistoryScenario.CURRENT_OVERDUE);
        UUID overdue = submit("WORKING_CAPITAL", form(true));
        processing.process(claims.claimNext().orElseThrow());
        assertReason(overdue, "CURRENT_OVERDUE_DEBT");
        assertThat(scoring.calls.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_clarification", Long.class)).isZero();
        source.action = id -> report(DemoCreditHistoryScenario.NO_HISTORY);
        UUID noHistory = submit("WORKING_CAPITAL", form(false));
        processing.process(claims.claimNext().orElseThrow());
        assertThat(asUser(() -> applications.getApplication(noHistory)).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(scoring.last.features().creditHistory().monthlyDebtPayment()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "CREDIT_HISTORY_UNAVAILABLE,false", "CREDIT_HISTORY_TIMEOUT,false", "CREDIT_HISTORY_INVALID_RESPONSE,false",
            "SCORING_UNAVAILABLE,true", "SCORING_TIMEOUT,true", "SCORING_INVALID_RESPONSE,true"
    })
    void externalFailuresBecomeTechnicalHold(String code, boolean reportStored) {
        if (reportStored) {
            scoring.action = (request, requestId) -> { throw new ScoringException(ScoringException.Code.valueOf(code), "fixture"); };
        } else {
            source.action = id -> { throw new CreditHistoryException(CreditHistoryException.Code.valueOf(code), "fixture"); };
        }
        UUID application = submit("WORKING_CAPITAL", form(false));
        processing.process(claims.claimNext().orElseThrow());
        var card = asUser(() -> applications.getApplication(application));
        assertThat(card.status()).isEqualTo(ApplicationStatus.TECHNICAL_HOLD);
        assertThat(card.processingError().code()).isEqualTo(code);
        assertThat(card.allowedActions()).containsExactly("RETRY");
        assertThat(jdbc.queryForObject("SELECT credit_history_snapshot IS NOT NULL FROM credit_assessment WHERE application_id = ?", Boolean.class, application)).isEqualTo(reportStored);
        assertThat(jdbc.queryForObject("SELECT risk_score IS NULL FROM credit_assessment WHERE application_id = ?", Boolean.class, application)).isTrue();
        assertThat(scoring.calls.get()).isEqualTo(reportStored ? 1 : 0);
    }

    @Test
    void rejectsInvalidReportAndIncompatibleRiskInsteadOfDeclining() {
        source.action = id -> {
            var original = report(DemoCreditHistoryScenario.DEFAULT);
            return new CreditHistoryReport("wrong", original.companyId(), original.source(), original.sourceVersion(),
                    original.reportId(), original.receivedAt(), original.profile());
        };
        UUID application = submit("WORKING_CAPITAL", form(false));
        processing.process(claims.claimNext().orElseThrow());
        assertThat(asUser(() -> applications.getApplication(application)).processingError().code()).isEqualTo("CREDIT_HISTORY_INVALID_RESPONSE");
        source.reset();
        scoring.action = (request, requestId) -> new RiskEstimate(4, scoringProperties.expectedModelVersion(), new BigDecimal("0.5"));
        UUID second = submit("WORKING_CAPITAL", form(false));
        processing.process(claims.claimNext().orElseThrow());
        assertThat(asUser(() -> applications.getApplication(second)).processingError().code()).isEqualTo("SCORING_INVALID_RESPONSE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRM_VALUE", "VALUE_CORRECTED"})
    void processesBothAnswersAndDoesNotRepeatConfirmedQuestion(String response) {
        UUID application = submit("WORKING_CAPITAL", form(true));
        processing.process(claims.claimNext().orElseThrow());
        var card = asUser(() -> applications.getApplication(application));
        assertThat(card.status()).isEqualTo(ApplicationStatus.NEEDS_INFO);
        assertThat(scoring.calls.get()).isZero();
        UUID question = card.requiredInformation().getFirst().clarificationId();
        if (response.equals("VALUE_CORRECTED")) {
            var before = card;
            card = asUser(() -> applications.saveForm(application, new SaveFormRequest(before.version(), before.form().withAverageMonthlyInflow(new BigDecimal("10000000.00")))));
        }
        var command = new SubmitApplicationRequest(card.version(), card.product().terms().version());
        command.setClarificationResponses(List.of(new ClarificationResponse(question, ResponseType.valueOf(response), null)));
        asUser(() -> submissions.submit(application, command, UUID.randomUUID(), UUID.randomUUID()));
        AssessmentClaim claim = claims.claimNext().orElseThrow();
        processing.process(claim);
        assertThat(asUser(() -> applications.getApplication(application)).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_clarification WHERE application_id = ?", Long.class, application)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT response_assessment_id FROM credit_clarification WHERE id = ?", UUID.class, question)).isEqualTo(claim.assessmentId());
        assertThat(source.calls.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryKeepsInputAndReusesOnlyAlreadyStoredHistory(boolean stored) {
        UUID application = submit("WORKING_CAPITAL", form(false));
        AssessmentClaim first = claims.claimNext().orElseThrow();
        String input = jdbc.queryForObject("SELECT input_snapshot::text FROM credit_assessment WHERE id = ?", String.class, first.assessmentId());
        if (stored) {
            state.storeHistory(first, report(DemoCreditHistoryScenario.DEFAULT));
        }
        source.action = id -> report(DemoCreditHistoryScenario.CURRENT_OVERDUE);
        expire(first);
        assertThat(recovery.recoverNext()).isTrue();
        AssessmentClaim next = claims.claimNext().orElseThrow();
        assertThat(next.assessmentId()).isEqualTo(first.assessmentId());
        assertThat(next.token()).isNotEqualTo(first.token());
        assertThat(next.executionCount()).isEqualTo(2);
        processing.process(next);
        assertThat(asUser(() -> applications.getApplication(application)).status()).isEqualTo(stored ? ApplicationStatus.APPROVED : ApplicationStatus.DECLINED);
        assertThat(source.calls.get()).isEqualTo(stored ? 0 : 1);
        assertThat(jdbc.queryForObject("SELECT input_snapshot::text FROM credit_assessment WHERE id = ?", String.class, first.assessmentId())).isEqualTo(input);
    }

    @Test
    void ignoresLateScoringResultFromOldWorker() throws Exception {
        UUID application = submit("WORKING_CAPITAL", form(false));
        AssessmentClaim old = claims.claimNext().orElseThrow();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scoring.action = (request, requestId) -> {
            if (scoring.calls.get() == 1) {
                entered.countDown();
                await(release);
                return new RiskEstimate(4, scoringProperties.expectedModelVersion(), BigDecimal.ONE);
            }
            return new RiskEstimate(4, scoringProperties.expectedModelVersion(), BigDecimal.ZERO);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var late = executor.submit(() -> processing.process(old));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                expire(old);
                assertThat(recovery.recoverNext()).isTrue();
                processing.process(claims.claimNext().orElseThrow());
                assertThat(asUser(() -> applications.getApplication(application)).status()).isEqualTo(ApplicationStatus.APPROVED);
            } finally {
                release.countDown();
            }
            late.get(5, TimeUnit.SECONDS);
        }
        assertThat(asUser(() -> applications.getApplication(application)).status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(finishEvents(application)).isEqualTo(1);
        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    void ignoresLateCreditReportAfterOwnershipChanged() throws Exception {
        UUID application = submit("WORKING_CAPITAL", form(false));
        AssessmentClaim old = claims.claimNext().orElseThrow();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.action = id -> {
            if (source.calls.get() == 1) {
                var original = report(DemoCreditHistoryScenario.DEFAULT);
                entered.countDown();
                await(release);
                return original;
            }
            return report(DemoCreditHistoryScenario.CURRENT_OVERDUE);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var late = executor.submit(() -> processing.process(old));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                expire(old);
                assertThat(recovery.recoverNext()).isTrue();
                processing.process(claims.claimNext().orElseThrow());
            } finally {
                release.countDown();
            }
            late.get(5, TimeUnit.SECONDS);
        }
        assertReason(application, "CURRENT_OVERDUE_DEBT");
        assertThat(scoring.calls.get()).isZero();
        assertThat(finishEvents(application)).isEqualTo(1);
    }

    @Test
    void stopsAfterLimitAndAllowsUserRetryWithFrozenData() {
        UUID application = submit("WORKING_CAPITAL", form(false));
        AssessmentClaim previous = null;
        for (int run = 1; run <= 3; run++) {
            previous = claims.claimNext().orElseThrow();
            assertThat(previous.executionCount()).isEqualTo(run);
            expire(previous);
            assertThat(recovery.recoverNext()).isTrue();
        }
        var card = asUser(() -> applications.getApplication(application));
        assertThat(card.status()).isEqualTo(ApplicationStatus.TECHNICAL_HOLD);
        assertThat(card.processingError().code()).isEqualTo("PROCESSING_INTERRUPTED");
        assertThat(card.allowedActions()).containsExactly("RETRY");
        assertThat(claims.claimNext()).isEmpty();
        String input = jdbc.queryForObject("SELECT input_snapshot::text FROM credit_assessment WHERE id = ?", String.class, previous.assessmentId());
        asUser(() -> retries.retry(application, new RetryApplicationRequest(card.version()), UUID.randomUUID(), UUID.randomUUID()));
        AssessmentClaim retry = claims.claimNext().orElseThrow();
        assertThat(retry.executionCount()).isEqualTo(1);
        assertThat(retry.assessmentId()).isNotEqualTo(previous.assessmentId());
        assertThat(jdbc.queryForObject("SELECT input_snapshot::text FROM credit_assessment WHERE id = ?", String.class, retry.assessmentId())).isEqualTo(input);
        processing.process(retry);
        assertThat(asUser(() -> applications.getApplication(application)).status()).isEqualTo(ApplicationStatus.APPROVED);
    }

    @Test
    void boundsActualCreditHistoryWait() {
        CountDownLatch release = new CountDownLatch(1);
        source.action = id -> { await(release); return report(DemoCreditHistoryScenario.DEFAULT); };
        var properties = new ProcessingProperties(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ZERO, 3);
        var bounded = new CreditHistoryService(source, validator, historyExecutor, properties);
        try {
            assertThatThrownBy(() -> bounded.getReport(USER.companyId(), NOW.atZone(ZoneOffset.UTC).toLocalDate()))
                    .isInstanceOfSatisfying(CreditHistoryException.class, ex -> assertThat(ex.getCode()).isEqualTo("CREDIT_HISTORY_TIMEOUT"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void workerCycleProcessesReadyAttempt() {
        UUID application = submit("WORKING_CAPITAL", form(false));
        new AssessmentWorker(recovery, claims, processing).runOnce();
        assertThat(asUser(() -> applications.getApplication(application)).status()).isEqualTo(ApplicationStatus.APPROVED);
    }

    @Test
    @EnabledIfSystemProperty(named = "processing.real-scoring", matches = "true")
    void executesPipelineAgainstActualPythonForAllProductsAndRiskDecline() {
        scoring.real = true;
        for (String code : List.of("WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE")) {
            UUID application = submit(code, DecisionTestData.context(code).form());
            processing.process(claims.claimNext().orElseThrow());
            assertThat(asUser(() -> applications.getApplication(application)).status()).isEqualTo(ApplicationStatus.APPROVED);
        }
        var original = form(false);
        var poor = new ApplicationForm(original.amount(), original.termMonths(), original.averageMonthlyInflow(), original.averageMonthlyOutflow(),
                null, null, null, null, BigDecimal.ZERO);
        UUID declined = submit("WORKING_CAPITAL", poor);
        processing.process(claims.claimNext().orElseThrow());
        assertReason(declined, "RISK_THRESHOLD_EXCEEDED");
    }

    private UUID submit(String code, ApplicationForm form) {
        return asUser(() -> {
            var draft = applications.create(new CreateApplicationRequest(code), UUID.randomUUID(), UUID.randomUUID()).body();
            UUID id = UUID.fromString(draft.path("id").asString());
            var saved = applications.saveForm(id, new SaveFormRequest(draft.path("version").asLong(), form));
            submissions.submit(id, new SubmitApplicationRequest(saved.version(), saved.product().terms().version()), UUID.randomUUID(), UUID.randomUUID());
            return id;
        });
    }

    private ApplicationForm form(boolean zero) {
        var original = DecisionTestData.context("WORKING_CAPITAL").form();
        return zero ? original.withAverageMonthlyInflow(new BigDecimal("0.00")) : original;
    }

    private CreditHistoryReport report(DemoCreditHistoryScenario scenario) {
        return new DemoCreditHistoryProvider(mapper, resources, clock, validator, scenario).getCreditHistory(USER.companyId());
    }

    private void expire(AssessmentClaim claim) {
        jdbc.update("UPDATE credit_assessment SET locked_until = clock_timestamp() - interval '1 millisecond' WHERE id = ?", claim.assessmentId());
    }

    private void assertReason(UUID application, String reason) {
        var card = asUser(() -> applications.getApplication(application));
        assertThat(card.status()).isEqualTo(ApplicationStatus.DECLINED);
        assertThat(card.decision().reasons().getFirst().code()).isEqualTo(reason);
    }

    private long finishEvents(UUID application) {
        return jdbc.queryForObject("SELECT count(*) FROM application_event WHERE application_id = ? AND from_status = 'CHECKING'", Long.class, application);
    }

    private <T> T asUser(Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, List.of()));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test barrier timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    static class ControlledHistory implements CreditHistoryProvider {
        final AtomicInteger calls = new AtomicInteger();
        final DemoCreditHistoryProvider delegate;
        volatile Function<UUID, CreditHistoryReport> action;

        ControlledHistory(DemoCreditHistoryProvider delegate) {
            this.delegate = delegate;
            reset();
        }

        @Override
        public CreditHistoryReport getCreditHistory(UUID companyId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            return action.apply(companyId);
        }

        void reset() {
            calls.set(0);
            action = delegate::getCreditHistory;
        }
    }

    static class ControlledScoring implements RiskScoringClient {
        final AtomicInteger calls = new AtomicInteger();
        final HttpRiskScoringClient actual;
        final ScoringProperties properties;
        volatile BiFunction<ScoringRequest, UUID, RiskEstimate> action;
        volatile ScoringRequest last;
        volatile boolean real;

        ControlledScoring(HttpRiskScoringClient actual, ScoringProperties properties) {
            this.actual = actual;
            this.properties = properties;
            reset();
        }

        @Override
        public RiskEstimate assess(ScoringRequest request, UUID requestId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            last = request;
            return real ? actual.assess(request, requestId) : action.apply(request, requestId);
        }

        void reset() {
            calls.set(0);
            real = false;
            last = null;
            action = (request, requestId) -> new RiskEstimate(4, properties.expectedModelVersion(), BigDecimal.ZERO);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Adapters {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean @Primary ControlledHistory controlledHistory(DemoCreditHistoryProvider provider) {
            return new ControlledHistory(provider);
        }

        @Bean @Primary ControlledScoring controlledScoring(HttpRiskScoringClient actual, ScoringProperties properties) {
            return new ControlledScoring(actual, properties);
        }
    }
}
