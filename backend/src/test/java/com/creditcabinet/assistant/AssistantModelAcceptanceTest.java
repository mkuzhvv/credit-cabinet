package com.creditcabinet.assistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.creditcabinet.assistant.api.SendMessageRequest;
import com.creditcabinet.assistant.api.SendMessageRequest.MessageContext;
import com.creditcabinet.assistant.api.MessageExchange;
import com.creditcabinet.assistant.service.AssistantService;
import com.creditcabinet.assistant.service.AssistantStateService;
import com.creditcabinet.identity.model.CurrentUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.username=credit_cabinet", "spring.datasource.password=", "credit.processing.enabled=false"
})
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
@EnabledIfSystemProperty(named = "assistant.real-model", matches = "true")
@Timeout(75)
class AssistantModelAcceptanceTest {

    private static final String SCHEMA = "stage13_real_" + UUID.randomUUID().toString().replace("-", "");
    private static final CurrentUser USER = new CurrentUser(UUID.fromString("b827db91-df42-4d75-b5e0-8f0a7a512ee1"),
            UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef"));
    @Autowired private AssistantStateService state;
    @Autowired private AssistantService assistant;
    @Autowired private JsonMapper mapper;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:5432/credit_cabinet?currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    static Stream<Arguments> cases() throws Exception {
        JsonNode cases = new JsonMapper().readTree(Files.readAllBytes(Path.of("../docs/contracts/llm/acceptance-cases.json"))).path("cases");
        return java.util.stream.StreamSupport.stream(cases.spliterator(), false)
                .filter(item -> System.getProperty("assistant.acceptance-case", item.path("id").asString()).equals(item.path("id").asString()))
                .map(item -> Arguments.of(item.path("id").asString(), item.path("message").asString(), item.path("expected")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void answersApprovedCasesThroughActualModelAndDatabase(String id, String text, JsonNode expected) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(USER, null, List.of()));
        try {
            UUID conversation = UUID.fromString(state.create(UUID.randomUUID()).body().path("id").asString());
            UUID key = UUID.randomUUID();
            long started = System.nanoTime();
            var response = assistant.send(conversation, new SendMessageRequest(text, new MessageContext(null, null, null)), key, UUID.randomUUID());
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            var answer = response.body().path("assistantMessage");
            var evidence = mapper.createObjectNode().put("id", id).put("ms", elapsed).put("answer", answer.path("text").asString());
            evidence.set("recommendedProductCode", answer.path("recommendedProductCode"));
            System.out.println("ASSISTANT_ACCEPTANCE " + mapper.writeValueAsString(evidence));
            assertThat(answer.path("recommendedProductCode")).as(expected.path("behavior").asString())
                    .isEqualTo(expected.path("recommendedProductCode"));
            assertThat(answer.path("text").asString()).isNotBlank();
            assertThat(state.history(conversation, 0, 50).items()).hasSize(2);
            var replay = assistant.send(conversation, new SendMessageRequest(text, new MessageContext(null, null, null)), key, UUID.randomUUID());
            assertThat(mapper.treeToValue(replay.body(), MessageExchange.class)).isEqualTo(mapper.treeToValue(response.body(), MessageExchange.class));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @AfterAll
    static void dropSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
}
