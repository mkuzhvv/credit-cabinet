package com.creditcabinet.credit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.credit.api.SubmitApplicationRequest;
import com.creditcabinet.credit.credithistory.CreditHistoryProvider;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.ClarificationRequirement;
import com.creditcabinet.credit.decision.DecisionTestData;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.AssessmentInput;
import com.creditcabinet.credit.model.AssessmentResult;
import com.creditcabinet.credit.model.AssessmentResult.ProcessingFailure;
import com.creditcabinet.credit.model.CreditClarification;
import com.creditcabinet.credit.persistence.ApplicationEventRepository;
import com.creditcabinet.credit.persistence.CreditClarificationRepository;
import com.creditcabinet.credit.service.ApplicationConflictException;
import com.creditcabinet.credit.service.SubmitApplicationService;
import com.creditcabinet.identity.model.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/credit_cabinet",
        "spring.datasource.username=credit_cabinet", "spring.datasource.password=", "credit.processing.enabled=false"
})
@AutoConfigureMockMvc
@TestExecutionListeners({ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class})
@EnabledIfSystemProperty(named = "applications.db-test", matches = "true")
@Timeout(30)
class ApplicationSubmissionTest {

    private static final String PATH = "/api/v1/applications";
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final CurrentUser USER = new CurrentUser(UUID.fromString("b827db91-df42-4d75-b5e0-8f0a7a512ee1"),
            UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef"));
    private static final CurrentUser OTHER = new CurrentUser(UUID.fromString("3b3b0d3a-7a37-49c3-b650-a721c0b17444"),
            UUID.fromString("58dd3ac1-9622-4be8-92cb-4ed38371a32b"));

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SubmitApplicationService submissions;
    @Autowired private CreditHistoryProvider histories;
    @Autowired private CreditClarificationRepository questions;
    @Autowired private ApplicationEventRepository events;
    @Autowired private PlatformTransactionManager transactions;
    private final Set<UUID> applicationIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> keys = ConcurrentHashMap.newKeySet();

    @AfterEach
    void removeOwnRecords() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for (UUID id : applicationIds) {
                jdbc.update("DELETE FROM application_event WHERE application_id = ?", id);
                jdbc.update("DELETE FROM credit_clarification WHERE application_id = ?", id);
                jdbc.update("UPDATE credit_application SET current_assessment_id = NULL, last_submitted_at = NULL, status = 'DRAFT' WHERE id = ?", id);
                jdbc.update("DELETE FROM credit_assessment WHERE application_id = ?", id);
                jdbc.update("DELETE FROM credit_application WHERE id = ?", id);
            }
            for (UUID key : keys) {
                jdbc.update("DELETE FROM idempotency_record WHERE user_id = ? AND idempotency_key = ?", USER.userId(), key);
            }
        });
    }

    @Test
    void acceptsAndReplaysOriginalResponseAfterStateAndVersionChanged() throws Exception {
        JsonNode draft = draft(false);
        UUID id = id(draft);
        UUID key = key();
        ObjectNode command = command(draft);
        JsonNode accepted = read(submit(id, key, command).andExpect(status().isAccepted())
                .andExpect(header().string("Location", PATH + "/" + id)));
        assertThat(accepted.path("version").asLong()).isEqualTo(draft.path("version").asLong() + 1);
        assertThat(accepted.path("status").asString()).isEqualTo("CHECKING");
        assertThat(accepted.path("pollAfterSeconds").asInt()).isEqualTo(2);
        assertThat(countAttempts(id)).isEqualTo(1);
        Map<String, Object> attempt = jdbc.queryForMap("SELECT processing_status, snapshot_schema_version, feature_schema_version, risk_cutoff FROM credit_assessment WHERE application_id = ?", id);
        assertThat(attempt).containsEntry("processing_status", "READY").containsEntry("snapshot_schema_version", 2).containsEntry("feature_schema_version", "4");
        assertThat(attempt.get("risk_cutoff").toString()).isEqualTo("0.50");
        assertThat(input(accepted).form().cashBalance()).isEqualByComparingTo("1000000.00");
        assertThat(card(id).path("allowedActions").isEmpty()).isTrue();
        finish(accepted, false, false);
        assertThat(card(id).path("processingError").path("code").asString()).isEqualTo("CREDIT_HISTORY_UNAVAILABLE");
        assertThat((Object) read(submit(id, key, command).andExpect(status().isAccepted()))).isEqualTo(accepted);
        assertThat(card(id).path("version").asLong()).isGreaterThan(accepted.path("version").asLong());
        submit(id, key, command.deepCopy().put("expectedVersion", 99)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(post(PATH + "/" + id + "/submit").with(csrf().asHeader()).with(authentication(auth(OTHER)))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidFormStaleVersionAndUnexpectedAnswersWithoutAccepting() throws Exception {
        JsonNode draft = create();
        UUID id = id(draft);
        submit(id, key(), command(draft)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(countAttempts(id)).isZero();
        JsonNode saved = save(id, draft.path("version").asLong(), form(false));
        submit(id, key(), command(draft)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        ObjectNode unexpected = command(saved);
        unexpected.set("clarificationResponses", mapper.valueToTree(List.of(Map.of("clarificationId", UUID.randomUUID(), "response", "CONFIRM_VALUE"))));
        submit(id, key(), unexpected).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CLARIFICATION_NOT_EXPECTED"));
        submit(id, key(), command(saved).putNull("clarificationResponses")).andExpect(status().isBadRequest());
        assertThat(countAttempts(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE resource_scope = ?", Long.class, id.toString())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCommandsCreateOnlyOneAttempt(boolean sameKey) throws Exception {
        JsonNode draft = draft(false);
        UUID id = id(draft);
        UUID first = key();
        UUID second = sameKey ? first : key();
        var command = mapper.treeToValue(command(draft), SubmitApplicationRequest.class);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> concurrentSubmit(id, command, first, start));
            var b = executor.submit(() -> concurrentSubmit(id, command, second, start));
            start.countDown();
            List<Object> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            if (sameKey) {
                assertThat(results).allMatch(result -> result instanceof StoredResponse);
                JsonNode firstBody = mapper.readTree(mapper.writeValueAsBytes(((StoredResponse) results.get(0)).body()));
                JsonNode secondBody = mapper.readTree(mapper.writeValueAsBytes(((StoredResponse) results.get(1)).body()));
                assertThat((Object) firstBody).isEqualTo(secondBody);
            } else {
                assertThat(results.stream().filter(StoredResponse.class::isInstance).count()).isEqualTo(1);
                var conflict = (ApplicationConflictException) results.stream().filter(ApplicationConflictException.class::isInstance).findFirst().orElseThrow();
                assertThat(conflict.getCode()).isEqualTo("APPLICATION_ALREADY_SUBMITTED");
            }
        }
        assertThat(countAttempts(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event WHERE application_id = ? AND to_status = 'CHECKING'", Long.class, id)).isEqualTo(1);
    }

    @Test
    void holdsCatalogLockUntilCommitAndReadsFrozenTerms() throws Exception {
        JsonNode draft = draft(false);
        UUID id = id(draft);
        var command = mapper.treeToValue(command(draft), SubmitApplicationRequest.class);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = executor.submit(() -> asUser(() -> new TransactionTemplate(transactions).execute(tx -> {
                StoredResponse response = submissions.submit(id, command, key(), UUID.randomUUID());
                accepted.countDown();
                await(release);
                return response;
            })));
            try {
                assertThat(accepted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                    jdbc.update("UPDATE credit_product SET terms_version = terms_version WHERE code = 'WORKING_CAPITAL'");
                })).isInstanceOf(DataAccessException.class)
                        .satisfies(ex -> assertThat(((SQLException) ex.getCause()).getSQLState()).isEqualTo("55P03"));
            } finally {
                release.countDown();
            }
            assertThat(call.get(10, TimeUnit.SECONDS).status()).isEqualTo(202);
        }
        String oldVersion = draft.path("product").path("terms").path("version").asString();
        try {
            jdbc.update("UPDATE credit_product SET terms_version = 'stage10-changed' WHERE code = 'WORKING_CAPITAL'");
            assertThat(card(id).path("product").path("terms").path("version").asString()).isEqualTo(oldVersion);
            JsonNode another = draft(false);
            submit(id(another), key(), command(another).put("expectedProductTermsVersion", oldVersion))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCT_TERMS_CHANGED"))
                    .andExpect(jsonPath("$.currentProductTermsVersion").value("stage10-changed"));
            assertThat(countAttempts(id(another))).isZero();
        } finally {
            jdbc.update("UPDATE credit_product SET terms_version = ? WHERE code = 'WORKING_CAPITAL'", oldVersion);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryCopiesOriginalInputPolicyAndOptionalReport(boolean withReport) throws Exception {
        JsonNode draft = draft(false);
        UUID id = id(draft);
        call(post(PATH + "/" + id + "/retry").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}")).andExpect(status().isConflict());
        JsonNode first = read(submit(id, key(), command(draft)).andExpect(status().isAccepted()));
        finish(first, false, withReport);
        JsonNode before = card(id);
        UUID retryKey = key();
        String body = mapper.writeValueAsString(Map.of("expectedVersion", before.path("version").asLong()));
        JsonNode retried = read(call(post(PATH + "/" + id + "/retry").header("Idempotency-Key", retryKey)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()));
        assertThat(retried.path("assessmentId").asString()).isNotEqualTo(first.path("assessmentId").asString());
        Map<String, Object> source = jdbc.queryForMap("SELECT input_snapshot, credit_history_snapshot, policy_version, feature_schema_version, expected_model_version, risk_cutoff FROM credit_assessment WHERE id = ?", assessmentId(first));
        Map<String, Object> copy = jdbc.queryForMap("SELECT input_snapshot, credit_history_snapshot, policy_version, feature_schema_version, expected_model_version, risk_cutoff FROM credit_assessment WHERE id = ?", assessmentId(retried));
        source.forEach((name, value) -> assertThat(String.valueOf(copy.get(name))).isEqualTo(String.valueOf(value)));
        assertThat(jdbc.queryForObject("SELECT previous_assessment_id FROM credit_assessment WHERE id = ?", UUID.class, assessmentId(retried))).isEqualTo(assessmentId(first));
        assertThat(jdbc.queryForObject("SELECT attempt_no FROM credit_assessment WHERE id = ?", Integer.class, assessmentId(retried))).isEqualTo(2);
        assertThat((Object) read(call(post(PATH + "/" + id + "/retry").header("Idempotency-Key", retryKey)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()))).isEqualTo(retried);
        assertThat(countAttempts(id)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRM_VALUE", "VALUE_CORRECTED"})
    void acceptsClarificationWithNewAttemptAndKeepsOriginalQuestion(String response) throws Exception {
        JsonNode draft = draft(true);
        UUID id = id(draft);
        JsonNode first = read(submit(id, key(), command(draft)).andExpect(status().isAccepted()));
        UUID questionId = finish(first, true, true);
        JsonNode before = card(id);
        assertThat(before.path("requiredInformation").path(0).path("observedValue").asString()).isEqualTo("0.00");
        ObjectNode forbidden = (ObjectNode) before.path("form").deepCopy();
        forbidden.put("cashBalance", "2000000.00");
        saveRequest(id, before.path("version").asLong(), forbidden).andExpect(status().isUnprocessableContent());
        if (response.equals("VALUE_CORRECTED")) {
            ObjectNode corrected = (ObjectNode) before.path("form").deepCopy();
            corrected.put("averageMonthlyInflow", "10000000.00");
            before = save(id, before.path("version").asLong(), corrected);
            assertThat(before.path("requiredInformation").path(0).path("observedValue").asString()).isEqualTo("0.00");
        }
        ObjectNode body = answer(command(before), questionId, response);
        UUID key = key();
        JsonNode accepted = read(submit(id, key, body).andExpect(status().isAccepted()));
        assertThat(countAttempts(id)).isEqualTo(2);
        assertThat(input(accepted).confirmations()).hasSize(response.equals("CONFIRM_VALUE") ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT credit_history_snapshot IS NULL FROM credit_assessment WHERE id = ?", Boolean.class, assessmentId(accepted))).isTrue();
        assertThat(jdbc.queryForObject("SELECT response_type FROM credit_clarification WHERE id = ?", String.class, questionId)).isEqualTo(response);
        assertThat(jdbc.queryForObject("SELECT response_assessment_id FROM credit_clarification WHERE id = ?", UUID.class, questionId)).isEqualTo(assessmentId(accepted));
        assertThat((Object) read(submit(id, key, body).andExpect(status().isAccepted()))).isEqualTo(accepted);
        if (response.equals("CONFIRM_VALUE")) {
            finish(accepted, false, true);
            JsonNode held = card(id);
            JsonNode retry = read(call(post(PATH + "/" + id + "/retry").header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedVersion", held.path("version").asLong()))))
                    .andExpect(status().isAccepted()));
            assertThat(input(retry).confirmations()).isEqualTo(input(accepted).confirmations());
        }
    }

    @Test
    void rejectsMissingDuplicateContradictoryAndStaleAnswers() throws Exception {
        JsonNode draft = draft(true);
        UUID id = id(draft);
        JsonNode accepted = read(submit(id, key(), command(draft)).andExpect(status().isAccepted()));
        UUID questionId = finish(accepted, true, true);
        ObjectNode body = command(card(id));
        submit(id, key(), body).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CLARIFICATION_RESPONSE_REQUIRED"));
        submit(id, key(), answer(body.deepCopy(), UUID.randomUUID(), "CONFIRM_VALUE")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLARIFICATION_STALE"));
        submit(id, key(), answer(body.deepCopy(), questionId, "VALUE_CORRECTED")).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CLARIFICATION_RESPONSE_CONFLICT"));
        ObjectNode duplicate = answer(body.deepCopy(), questionId, "CONFIRM_VALUE");
        duplicate.withArray("clarificationResponses").add(duplicate.path("clarificationResponses").path(0).deepCopy());
        submit(id, key(), duplicate).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CLARIFICATION_RESPONSE_CONFLICT"));
        jdbc.update("UPDATE credit_clarification SET reporting_period_start = reporting_period_start - 1 WHERE id = ?", questionId);
        submit(id, key(), answer(body.deepCopy(), questionId, "CONFIRM_VALUE")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLARIFICATION_STALE"));
        assertThat(countAttempts(id)).isEqualTo(1);
        assertThat(card(id).path("status").asString()).isEqualTo("NEEDS_INFO");
    }

    @Test
    void rollsBackAttemptStateAndIdempotencyWhenEventWriteFails() throws Exception {
        JsonNode draft = draft(false);
        UUID id = id(draft);
        events.recordTransition(id, draft.path("version").asLong() + 1, null, ApplicationStatus.DRAFT,
                ApplicationStatus.CHECKING, "TEST_FAILURE", USER.userId(), UUID.randomUUID(), NOW);
        UUID key = key();
        submit(id, key, command(draft)).andExpect(status().isInternalServerError());
        assertThat(countAttempts(id)).isZero();
        assertThat(card(id).path("status").asString()).isEqualTo("DRAFT");
        assertThat(card(id).path("version").asLong()).isEqualTo(draft.path("version").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?", Long.class, key)).isZero();
    }

    private UUID finish(JsonNode accepted, boolean needsInfo, boolean withReport) {
        UUID id = UUID.fromString(accepted.path("applicationId").asString());
        UUID assessment = assessmentId(accepted);
        AssessmentInput input = input(accepted);
        return new TransactionTemplate(transactions).execute(tx -> {
            UUID questionId = null;
            if (needsInfo) {
                var requirement = new ClarificationRequirement("form.averageMonthlyInflow", "ZERO_INFLOW_CONFIRMATION",
                        "Подтвердите нулевые поступления", new java.math.BigDecimal("0.00"), input.reportingPeriod());
                questionId = questions.saveAndFlush(new CreditClarification(id, assessment, requirement, NOW)).getId();
            }
            ApplicationStatus outcome = needsInfo ? ApplicationStatus.NEEDS_INFO : ApplicationStatus.TECHNICAL_HOLD;
            var failure = needsInfo ? null : new ProcessingFailure(withReport ? "SCORING_UNAVAILABLE" : "CREDIT_HISTORY_UNAVAILABLE", "Источник недоступен", NOW);
            var result = new AssessmentResult(1, null, questionId == null ? List.of() : List.of(questionId), failure);
            String report = withReport ? mapper.writeValueAsString(histories.getCreditHistory(USER.companyId())) : null;
            jdbc.update("UPDATE credit_assessment SET processing_status = 'FINISHED', outcome = ?, result_payload = CAST(? AS jsonb), finished_at = ?, credit_history_snapshot = CAST(? AS jsonb) WHERE id = ?",
                    outcome.name(), mapper.writeValueAsString(result), java.sql.Timestamp.from(NOW), report, assessment);
            jdbc.update("UPDATE credit_application SET status = ?, version = version + 1 WHERE id = ?", outcome.name(), id);
            long version = jdbc.queryForObject("SELECT version FROM credit_application WHERE id = ?", Long.class, id);
            events.recordTransition(id, version, assessment, ApplicationStatus.CHECKING, outcome, "TEST_FIXTURE", USER.userId(), UUID.randomUUID(), NOW);
            return questionId;
        });
    }

    private AssessmentInput input(JsonNode accepted) {
        return mapper.readValue(jdbc.queryForObject("SELECT input_snapshot::text FROM credit_assessment WHERE id = ?", String.class, assessmentId(accepted)), AssessmentInput.class);
    }

    private Object concurrentSubmit(UUID id, SubmitApplicationRequest command, UUID key, CountDownLatch start) {
        await(start);
        return asUser(() -> {
            try {
                return submissions.submit(id, command, key, UUID.randomUUID());
            } catch (ApplicationConflictException ex) {
                return ex;
            }
        });
    }

    private <T> T asUser(Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(auth(USER));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode create() throws Exception {
        JsonNode draft = read(call(post(PATH).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"WORKING_CAPITAL\"}")).andExpect(status().isCreated()));
        applicationIds.add(id(draft));
        return draft;
    }

    private JsonNode draft(boolean zero) throws Exception {
        JsonNode created = create();
        return save(id(created), created.path("version").asLong(), form(zero));
    }

    private ObjectNode form(boolean zero) {
        ObjectNode form = (ObjectNode) mapper.valueToTree(DecisionTestData.context("WORKING_CAPITAL").form());
        if (zero) {
            form.put("averageMonthlyInflow", "0.00");
        }
        return form;
    }

    private ObjectNode command(JsonNode card) {
        return mapper.createObjectNode().put("expectedVersion", card.path("version").asLong())
                .put("expectedProductTermsVersion", card.path("product").path("terms").path("version").asString());
    }

    private ObjectNode answer(ObjectNode body, UUID questionId, String response) {
        body.set("clarificationResponses", mapper.valueToTree(List.of(Map.of("clarificationId", questionId, "response", response))));
        return body;
    }

    private ResultActions submit(UUID id, UUID key, ObjectNode command) throws Exception {
        return call(post(PATH + "/" + id + "/submit").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command)));
    }

    private JsonNode save(UUID id, long version, ObjectNode form) throws Exception {
        return read(saveRequest(id, version, form).andExpect(status().isOk()));
    }

    private ResultActions saveRequest(UUID id, long version, ObjectNode form) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("expectedVersion", version);
        body.set("form", form);
        return call(put(PATH + "/" + id + "/form").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private JsonNode card(UUID id) throws Exception {
        return read(call(get(PATH + "/" + id)).andExpect(status().isOk()));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(auth(USER))).with(csrf().asHeader()));
    }

    private JsonNode read(ResultActions response) throws Exception {
        return mapper.readTree(response.andReturn().getResponse().getContentAsString());
    }

    private UUID id(JsonNode card) {
        return UUID.fromString(card.path("id").asString());
    }

    private UUID assessmentId(JsonNode accepted) {
        return UUID.fromString(accepted.path("assessmentId").asString());
    }

    private UUID key() {
        UUID key = UUID.randomUUID();
        keys.add(key);
        return key;
    }

    private long countAttempts(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM credit_assessment WHERE application_id = ?", Long.class, id);
    }

    private static UsernamePasswordAuthenticationToken auth(CurrentUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
