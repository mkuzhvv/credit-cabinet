package com.creditcabinet.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import com.creditcabinet.assistant.api.SendMessageRequest;
import com.creditcabinet.assistant.api.SendMessageRequest.MessageContext;
import com.creditcabinet.assistant.integration.AssistantGateway;
import com.creditcabinet.assistant.integration.AssistantGeneration;
import com.creditcabinet.assistant.integration.AssistantGeneration.ModelMetadata;
import com.creditcabinet.assistant.integration.AssistantReply;
import com.creditcabinet.assistant.model.AssistantContext;
import com.creditcabinet.assistant.service.AssistantException;
import com.creditcabinet.assistant.service.AssistantProperties;
import com.creditcabinet.assistant.service.AssistantReplyValidator;
import com.creditcabinet.assistant.service.AssistantService;
import com.creditcabinet.assistant.service.AssistantStateService;
import com.creditcabinet.common.idempotency.IdempotencyConflictException;
import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.credit.api.CreateApplicationRequest;
import com.creditcabinet.credit.api.SaveFormRequest;
import com.creditcabinet.credit.api.SubmitApplicationRequest;
import com.creditcabinet.credit.decision.DecisionTestData;
import com.creditcabinet.credit.service.ApplicationService;
import com.creditcabinet.credit.service.SubmitApplicationService;
import com.creditcabinet.identity.model.CurrentUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.username=credit_cabinet", "spring.datasource.password=", "credit.processing.enabled=false"
})
@AutoConfigureMockMvc
@TestExecutionListeners({ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class})
@EnabledIfSystemProperty(named = "applications.db-test", matches = "true")
@Timeout(30)
class AssistantServiceTest {

    private static final String PATH = "/api/v1/assistant/conversations";
    private static final String SCHEMA = "stage12_" + UUID.randomUUID().toString().replace("-", "");
    private static final CurrentUser USER = new CurrentUser(UUID.fromString("b827db91-df42-4d75-b5e0-8f0a7a512ee1"),
            UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef"));
    private static final CurrentUser OTHER = new CurrentUser(UUID.fromString("3b3b0d3a-7a37-49c3-b650-a721c0b17444"),
            UUID.fromString("58dd3ac1-9622-4be8-92cb-4ed38371a32b"));

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AssistantService assistant;
    @Autowired private AssistantStateService state;
    @Autowired private AssistantReplyValidator validator;
    @Autowired private ControlledGateway gateway;
    @Autowired private ApplicationService applications;
    @Autowired private SubmitApplicationService submissions;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired @Qualifier("assistantExecutor") private ExecutorService assistantExecutor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:5432/credit_cabinet?currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterEach
    void clean() {
        gateway.reset();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM assistant_message");
            jdbc.update("DELETE FROM assistant_conversation");
            jdbc.update("DELETE FROM application_event");
            jdbc.update("DELETE FROM credit_clarification");
            jdbc.update("UPDATE credit_application SET current_assessment_id = NULL, last_submitted_at = NULL, status = 'DRAFT'");
            jdbc.update("DELETE FROM credit_assessment");
            jdbc.update("DELETE FROM credit_application");
            jdbc.update("DELETE FROM idempotency_record");
            jdbc.update("UPDATE credit_product SET terms_version = 'demo-working-capital-2' WHERE code = 'WORKING_CAPITAL'");
        });
    }

    @AfterAll
    static void dropSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void createsReplaysAndProtectsConversationByUserAndCompany() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode created = read(call(post(PATH).header("Idempotency-Key", key)).andExpect(status().isCreated()));
        assertThat((Object) read(call(post(PATH).header("Idempotency-Key", key)).andExpect(status().isCreated()))).isEqualTo(created);
        UUID id = UUID.fromString(created.path("id").asString());
        call(get(path(id))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.lastSequence").value(0)).andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get(path(id)).with(authentication(auth(OTHER)))).andExpect(status().isNotFound());
        CurrentUser sameCompany = new CurrentUser(UUID.randomUUID(), USER.companyId());
        jdbc.update("INSERT INTO app_user (id, company_id, display_name) VALUES (?, ?, 'Другой представитель')", sameCompany.userId(), sameCompany.companyId());
        mvc.perform(get(path(id)).with(authentication(auth(sameCompany)))).andExpect(status().isNotFound());
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized());
        call(get(path(id)).param("limit", "0")).andExpect(status().isUnprocessableContent());
        assertThat(gateway.calls.get()).isZero();
    }

    @Test
    void savesPairMetadataPaginationAndReplaysWithoutGeneration() throws Exception {
        UUID conversation = conversation();
        UUID key = UUID.randomUUID();
        var request = request("Какой кредит выбрать?", null, null, null);
        JsonNode first = read(send(conversation, request, key).andExpect(status().isOk()));
        assertThat(first.path("userMessage").path("sequence").asLong()).isEqualTo(1);
        assertThat(first.path("assistantMessage").path("sequence").asLong()).isEqualTo(2);
        assertThat(first.path("assistantMessage").path("replyToMessageId").asString()).isEqualTo(first.path("userMessage").path("id").asString());
        assertThat(gateway.last.context().application()).isNull();
        assertThat(gateway.last.context().catalog()).hasSize(5);
        assertThat(mapper.valueToTree(gateway.last).propertyNames()).containsExactlyInAnyOrder("schemaVersion", "conversationId", "message", "history", "context");
        send(conversation, request("Уточни условия", null, null, null), UUID.randomUUID()).andExpect(status().isOk());
        assertThat(gateway.last.history()).hasSize(2);
        assertThat((Object) read(send(conversation, request, key).andExpect(status().isOk()))).isEqualTo(first);
        assertThat(gateway.calls.get()).isEqualTo(2);
        send(conversation, request("Другое тело", null, null, null), key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        call(get(path(conversation)).param("limit", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.lastSequence").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));
        call(get(path(conversation)).param("afterSequence", "3").param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sequence").value(4)).andExpect(jsonPath("$.hasMore").value(false));
        call(get(path(conversation)).param("afterSequence", "99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.lastSequence").value(99));
        assertThat(jdbc.queryForObject("SELECT generation_metadata->>'inputSchemaVersion' FROM assistant_message WHERE role='ASSISTANT' LIMIT 1", String.class)).isEqualTo("2");
    }

    @Test
    void buildsMinimalCashContextAndUsesFrozenApplicationTerms() throws Exception {
        UUID application = application("WORKING_CAPITAL", true);
        jdbc.update("UPDATE credit_product SET terms_version = 'changed' WHERE code = 'WORKING_CAPITAL'");
        send(conversation(), request("Что за остаток?", application, "WORKING_CAPITAL", "form.cashBalance"), UUID.randomUUID()).andExpect(status().isOk());
        var context = gateway.last.context();
        assertThat(context.application().fieldValues()).extracting(value -> value.fieldCode()).containsExactly("form.cashBalance", "form.averageMonthlyOutflow");
        assertThat(context.application().fieldValues().getFirst().value()).isEqualTo("1000000.00");
        assertThat(context.application().reportingPeriod().months()).isEqualTo(6);
        assertThat(context.catalog().stream().filter(product -> product.code().equals("WORKING_CAPITAL")).findFirst().orElseThrow().termsVersion()).isEqualTo("demo-working-capital-2");
        assertThat(context.fieldDefinitions()).anyMatch(field -> field.code().equals("form.cashBalance"));
        String serialized = mapper.writeValueAsString(gateway.last);
        assertThat(serialized).doesNotContain("creditHistory", "sourceVersion", "companyId", "reportId");
        UUID government = application("GOVERNMENT_CONTRACT_LINE", false);
        send(conversation(), request("Какой номер указать?", government, null, "form.governmentContract.number"), UUID.randomUUID()).andExpect(status().isOk());
        assertThat(gateway.last.context().application().fieldValues()).isEmpty();
        assertThat(mapper.writeValueAsString(gateway.last)).doesNotContain("DEMO-44-001");
    }

    @Test
    void rejectsForeignApplicationAndInvalidContextBeforeCallingGateway() throws Exception {
        UUID application = application("WORKING_CAPITAL", false);
        UUID foreign = asUser(OTHER, () -> {
            var created = applications.create(new CreateApplicationRequest("WORKING_CAPITAL"), UUID.randomUUID(), UUID.randomUUID());
            return UUID.fromString(created.body().path("id").asString());
        });
        UUID conversation = conversation();
        send(conversation, request("Вопрос", foreign, null, null), UUID.randomUUID()).andExpect(status().isNotFound());
        send(conversation, request("Вопрос", application, "LONG_TERM", null), UUID.randomUUID()).andExpect(status().isUnprocessableContent());
        send(conversation, request("Вопрос", null, "EQUIPMENT", null), UUID.randomUUID()).andExpect(status().isUnprocessableContent());
        send(conversation, request("Вопрос", application, null, "form.projectBudget"), UUID.randomUUID()).andExpect(status().isUnprocessableContent());
        send(conversation, request("Вопрос", null, null, "form.unknown"), UUID.randomUUID()).andExpect(status().isUnprocessableContent());
        assertThat(gateway.calls.get()).isZero();
        assertEmpty(conversation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASSISTANT_UNAVAILABLE", "ASSISTANT_TIMEOUT", "ASSISTANT_INVALID_RESPONSE"})
    void releasesFailedGenerationAndAllowsSameKeyToRetry(String code) throws Exception {
        gateway.action = input -> { throw switch (code) {
            case "ASSISTANT_TIMEOUT" -> AssistantException.timeout(null);
            case "ASSISTANT_INVALID_RESPONSE" -> AssistantException.invalidResponse();
            default -> AssistantException.unavailable();
        }; };
        UUID conversation = conversation();
        UUID key = UUID.randomUUID();
        var body = request("Вопрос", null, null, null);
        int expected = code.equals("ASSISTANT_TIMEOUT") ? 504 : code.equals("ASSISTANT_INVALID_RESPONSE") ? 502 : 503;
        send(conversation, body, key).andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code));
        assertEmpty(conversation);
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_record WHERE idempotency_key = ?", String.class, key)).isEqualTo("RETRYABLE_FAILURE");
        gateway.reset();
        send(conversation, body, key).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_message WHERE conversation_id = ?", Long.class, conversation)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "blank", "html", "unknown", "oversized", "metadata"})
    void rejectsInvalidReplyWithoutSavingHalfPair(String variant) throws Exception {
        gateway.action = input -> switch (variant) {
            case "null" -> null;
            case "metadata" -> new AssistantGeneration(new AssistantReply("Ответ", null), null);
            case "unknown" -> reply("Ответ", "EQUIPMENT");
            case "oversized" -> reply("я".repeat(3001), null);
            case "html" -> reply("<b>Ответ</b>", null);
            default -> reply("   ", null);
        };
        UUID conversation = conversation();
        send(conversation, request("Вопрос", null, null, null), UUID.randomUUID()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ASSISTANT_INVALID_RESPONSE"));
        assertEmpty(conversation);
    }

    @Test
    void preventsParallelQuestionsAndRepeatsOfInProgressCommand() throws Exception {
        UUID conversation = conversation();
        UUID key = UUID.randomUUID();
        var body = request("Вопрос", null, null, null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gateway.action = input -> { entered.countDown(); await(release); return reply("Ответ", null); };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> asUser(USER, () -> assistant.send(conversation, body, key, UUID.randomUUID())));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                send(conversation, body, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONVERSATION_BUSY"));
                send(conversation, body, key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_IN_PROGRESS"));
                assertThat(gateway.calls.get()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldAnswerCannotOverwriteNewGeneration(boolean sameKey) throws Exception {
        UUID conversation = conversation();
        UUID originalKey = UUID.randomUUID();
        var body = request("Вопрос", null, null, null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gateway.action = input -> {
            if (gateway.calls.get() == 1) {
                entered.countDown();
                await(release);
                return reply("Старый ответ", null);
            }
            return reply("Новый ответ", null);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> {
                try {
                    return (Object) asUser(USER, () -> assistant.send(conversation, body, originalKey, UUID.randomUUID()));
                } catch (RuntimeException ex) {
                    return ex;
                }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE assistant_conversation SET generation_locked_until = clock_timestamp() - interval '1 millisecond' WHERE id = ?", conversation);
                if (sameKey) {
                    jdbc.update("UPDATE idempotency_record SET locked_until = clock_timestamp() - interval '1 millisecond' WHERE idempotency_key = ?", originalKey);
                }
                send(conversation, body, sameKey ? originalKey : UUID.randomUUID()).andExpect(status().isOk())
                        .andExpect(jsonPath("$.assistantMessage.text").value("Новый ответ"));
            } finally {
                release.countDown();
            }
            Object result = old.get(5, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(sameKey ? IdempotencyConflictException.class : AssistantException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_message WHERE conversation_id = ?", Long.class, conversation)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT text FROM assistant_message WHERE conversation_id = ? AND role='ASSISTANT'", String.class, conversation)).isEqualTo("Новый ответ");
    }

    @Test
    void rollsBackBothMessagesWhenGenerationExpiresBeforeFinalWrite() throws Exception {
        gateway.action = input -> {
            jdbc.update("UPDATE assistant_conversation SET generation_locked_until = clock_timestamp() - interval '1 millisecond' WHERE id = ?", input.conversationId());
            return reply("Ответ", null);
        };
        UUID conversation = conversation();
        send(conversation, request("Вопрос", null, null, null), UUID.randomUUID()).andExpect(status().isConflict());
        assertEmpty(conversation);
    }

    @Test
    void timeoutReleasesGenerationAndHistoryKeepsOnlyLastTwentyMessages() throws Exception {
        UUID conversation = conversation();
        CountDownLatch release = new CountDownLatch(1);
        gateway.action = input -> { await(release); return reply("Ответ", null); };
        var shortBudget = new AssistantProperties(Duration.ofMillis(100), Duration.ofSeconds(75));
        var bounded = new AssistantService(state, gateway, validator, shortBudget, assistantExecutor);
        try {
            assertThatThrownBy(() -> asUser(USER, () -> bounded.send(conversation, request("Вопрос", null, null, null), UUID.randomUUID(), UUID.randomUUID())))
                    .isInstanceOfSatisfying(AssistantException.class, ex -> assertThat(ex.getCode()).isEqualTo("ASSISTANT_TIMEOUT"));
        } finally {
            release.countDown();
        }
        assertEmpty(conversation);
        gateway.reset();
        for (int i = 0; i < 12; i++) {
            send(conversation, request("Вопрос " + i, null, null, null), UUID.randomUUID()).andExpect(status().isOk());
        }
        assertThat(gateway.last.history()).hasSize(20);
        assertThat(gateway.last.history().getFirst().text()).isEqualTo("Вопрос 1");
        assertThat(gateway.last.history().getFirst().role().name()).isEqualTo("USER");
        assertThat(gateway.last.history().getLast().role().name()).isEqualTo("ASSISTANT");
    }

    private UUID application(String code, boolean submitted) {
        return asUser(USER, () -> {
            var created = applications.create(new CreateApplicationRequest(code), UUID.randomUUID(), UUID.randomUUID());
            UUID id = UUID.fromString(created.body().path("id").asString());
            var saved = applications.saveForm(id, new SaveFormRequest(0L, DecisionTestData.context(code).form()));
            if (submitted) {
                submissions.submit(id, new SubmitApplicationRequest(saved.version(), saved.product().terms().version()), UUID.randomUUID(), UUID.randomUUID());
            }
            return id;
        });
    }

    private UUID conversation() throws Exception {
        return UUID.fromString(read(call(post(PATH).header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))).path("id").asString());
    }

    private void assertEmpty(UUID conversation) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_message WHERE conversation_id = ?", Long.class, conversation)).isZero();
        assertThat(jdbc.queryForObject("SELECT next_sequence = 1 AND active_generation_token IS NULL FROM assistant_conversation WHERE id = ?", Boolean.class, conversation)).isTrue();
    }

    private SendMessageRequest request(String text, UUID applicationId, String productCode, String fieldCode) {
        return new SendMessageRequest(text, new MessageContext(applicationId, productCode, fieldCode));
    }

    private ResultActions send(UUID conversation, SendMessageRequest body, UUID key) throws Exception {
        return call(post(path(conversation)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private String path(UUID conversation) { return PATH + "/" + conversation + "/messages"; }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(auth(USER))).with(csrf().asHeader()));
    }

    private JsonNode read(ResultActions response) throws Exception {
        return mapper.readTree(response.andReturn().getResponse().getContentAsString());
    }

    private static UsernamePasswordAuthenticationToken auth(CurrentUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
    }

    private static <T> T asUser(CurrentUser user, Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(auth(user));
        try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test barrier timed out"); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static AssistantGeneration reply(String text, String code) {
        return new AssistantGeneration(new AssistantReply(text, code), new ModelMetadata("test-model", "test-digest", "test-prompt", Map.of("temperature", "0")));
    }

    static class ControlledGateway implements AssistantGateway {
        final AtomicInteger calls = new AtomicInteger();
        volatile AssistantContext last;
        volatile Function<AssistantContext, AssistantGeneration> action = input -> reply("Тестовый ответ", "LONG_TERM");

        @Override
        public AssistantGeneration generate(AssistantContext context, UUID requestId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            last = context;
            return action.apply(context);
        }

        void reset() {
            calls.set(0);
            last = null;
            action = input -> reply("Тестовый ответ", "LONG_TERM");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Adapters {
        @Bean @Primary ControlledGateway gateway() { return new ControlledGateway(); }
        @Bean @Primary Clock clock() { return Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC); }
    }
}
