package com.creditcabinet.credit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.creditcabinet.credit.api.CreateApplicationRequest;
import com.creditcabinet.credit.api.SaveFormRequest;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.service.ApplicationService;
import com.creditcabinet.identity.model.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
        "spring.datasource.username=credit_cabinet", "spring.datasource.password="
})
@AutoConfigureMockMvc
@TestExecutionListeners({ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class})
@EnabledIfSystemProperty(named = "applications.db-test", matches = "true")
@Timeout(30)
class ApplicationServiceTest {

    private static final String PATH = "/api/v1/applications";
    private static final CurrentUser USER = new CurrentUser(UUID.fromString("b827db91-df42-4d75-b5e0-8f0a7a512ee1"),
            UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef"));
    private static final CurrentUser OTHER = new CurrentUser(UUID.fromString("3b3b0d3a-7a37-49c3-b650-a721c0b17444"),
            UUID.fromString("58dd3ac1-9622-4be8-92cb-4ed38371a32b"));

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationService service;
    @Autowired private PlatformTransactionManager transactions;
    private final Set<UUID> applicationIds = new HashSet<>();
    private final Set<UUID> keys = new HashSet<>();

    @AfterEach
    void removeOwnRecords() {
        for (UUID id : applicationIds) {
            jdbc.update("DELETE FROM application_event WHERE application_id = ?", id);
            jdbc.update("DELETE FROM credit_application WHERE id = ?", id);
        }
        for (UUID key : keys) {
            jdbc.update("DELETE FROM idempotency_record WHERE user_id = ? AND operation = 'applications.create.v1' AND idempotency_key = ?",
                    USER.userId(), key);
        }
    }

    @Test
    void createsSavesReadsAndReplaysOriginalCreation() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode draft = create("WORKING_CAPITAL", key);
        UUID id = UUID.fromString(draft.path("id").asString());
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("version").asLong()).isZero();
        assertThat(draft.path("calculationDate").asString()).isEqualTo("2026-10-25");
        assertThat(draft.path("reportingPeriod").path("startDate").asString()).isEqualTo("2026-04-01");
        assertThat(draft.path("reportingPeriod").path("endDate").asString()).isEqualTo("2026-09-30");
        ObjectNode form = (ObjectNode) draft.path("form").deepCopy();
        form.put("amount", "500000.00");
        JsonNode saved = read(save(id, 0, form).andExpect(status().isOk()));
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("form").path("amount").asString()).isEqualTo("500000.00");
        assertThat(read(call(get(PATH + "/" + id)).andExpect(status().isOk()))).isEqualTo(saved);
        assertThat(create("WORKING_CAPITAL", key)).isEqualTo(draft);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event WHERE application_id = ?", Long.class, id)).isEqualTo(1);
        call(post(PATH).with(csrf().asHeader()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"OVERDRAFT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        form.putNull("amount");
        save(id, 1, form).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(read(call(get(PATH + "/" + id)).andExpect(status().isOk())).path("form").path("amount").isNull()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"WORKING_CAPITAL,6", "OVERDRAFT,3", "REVOLVING_LINE,6", "LONG_TERM,12", "GOVERNMENT_CONTRACT_LINE,12"})
    void savesApplicablePartialFormsForEveryProduct(String code, int months) throws Exception {
        JsonNode draft = create(code, UUID.randomUUID());
        UUID id = UUID.fromString(draft.path("id").asString());
        assertThat(draft.path("reportingPeriod").path("months").asInt()).isEqualTo(months);
        ObjectNode form = (ObjectNode) draft.path("form").deepCopy();
        switch (code) {
            case "LONG_TERM" -> form.put("financingPurpose", "Покупка печи").put("projectBudget", "600000.00");
            case "REVOLVING_LINE" -> form.put("trancheTermMonths", 3);
            case "GOVERNMENT_CONTRACT_LINE" -> form.set("governmentContract", mapper.readTree("""
                    {"lawCode":"44_FZ","number":"DEMO-44-001","contractAmount":null,"advanceAmount":null,
                     "executionEndDate":null,"finalPaymentDate":null}
                    """));
            default -> form.put("averageMonthlyInflow", "0.00");
        }
        save(id, 0, form).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertThat(read(call(get(PATH + "/" + id)).andExpect(status().isOk())).path("form")).isEqualTo(form);
        if (code.equals("GOVERNMENT_CONTRACT_LINE")) {
            form.putNull("governmentContract");
            save(id, 1, form).andExpect(status().isOk());
            assertThat(read(call(get(PATH + "/" + id)).andExpect(status().isOk())).path("form").path("governmentContract").isNull()).isTrue();
        }
    }

    @Test
    void invalidSaveAndStaleVersionDoNotOverwriteTheForm() throws Exception {
        JsonNode draft = create("WORKING_CAPITAL", UUID.randomUUID());
        UUID id = UUID.fromString(draft.path("id").asString());
        ObjectNode form = (ObjectNode) draft.path("form").deepCopy();
        form.remove("amount");
        save(id, 0, form).andExpect(status().isBadRequest());
        form.put("amount", "12.001");
        save(id, 0, form).andExpect(status().isBadRequest());
        form.put("amount", "500000.00").put("trancheTermMonths", 3);
        save(id, 0, form).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("form.trancheTermMonths"));
        assertThat(read(call(get(PATH + "/" + id)).andExpect(status().isOk()))).isEqualTo(draft);
        form.putNull("trancheTermMonths");
        JsonNode saved = read(save(id, 0, form).andExpect(status().isOk()));
        form.put("amount", "900000.00");
        save(id, 0, form).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(read(call(get(PATH + "/" + id)).andExpect(status().isOk()))).isEqualTo(saved);
    }

    @Test
    void enforcesCompanyAccessAndSupportsFilteredPagination() throws Exception {
        UUID first = UUID.fromString(create("WORKING_CAPITAL", UUID.randomUUID()).path("id").asString());
        UUID second = UUID.fromString(create("OVERDRAFT", UUID.randomUUID()).path("id").asString());
        mvc.perform(get(PATH + "/" + first).with(authentication(auth(OTHER)))).andExpect(status().isNotFound());
        mvc.perform(put(PATH + "/" + first + "/form").with(authentication(auth(OTHER))).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new SaveFormRequest(0L, ApplicationForm.empty()))))
                .andExpect(status().isNotFound());
        JsonNode others = read(mvc.perform(get(PATH).with(authentication(auth(OTHER)))).andExpect(status().isOk()));
        for (JsonNode item : others.path("items")) {
            assertThat(item.path("id").asString()).isNotIn(first.toString(), second.toString());
        }
        JsonNode list = read(call(get(PATH).param("status", "DRAFT").param("size", "100")).andExpect(status().isOk()));
        assertThat(list.path("items").size()).isGreaterThanOrEqualTo(2);
        String previous = null;
        Instant previousTime = null;
        for (JsonNode item : list.path("items")) {
            Instant updatedAt = Instant.parse(item.path("updatedAt").asString());
            if (previousTime != null) {
                assertThat(updatedAt).isBeforeOrEqualTo(previousTime);
                if (updatedAt.equals(previousTime)) {
                    assertThat(item.path("id").asString()).isLessThan(previous);
                }
            }
            previous = item.path("id").asString();
            previousTime = updatedAt;
        }
        call(get(PATH).param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        call(get(PATH).param("status", "APPROVED")).andExpect(status().isOk());
        call(get(PATH).param("size", "101")).andExpect(status().isUnprocessableContent());
        call(get(PATH + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnknownProductWithoutKeepingAnIdempotencyRecord() throws Exception {
        UUID key = UUID.randomUUID();
        keys.add(key);
        call(post(PATH).with(csrf().asHeader()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"UNKNOWN_PRODUCT\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("productCode"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?", Long.class, key)).isZero();
    }

    @Test
    void creationEventAndIdempotencyRollBackTogether() {
        UUID key = UUID.randomUUID();
        keys.add(key);
        SecurityContextHolder.getContext().setAuthentication(auth(USER));
        UUID id;
        try {
            id = new TransactionTemplate(transactions).execute(tx -> {
                var response = service.create(new CreateApplicationRequest("WORKING_CAPITAL"), key, UUID.randomUUID());
                tx.setRollbackOnly();
                return UUID.fromString(response.body().path("id").asString());
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_application WHERE id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event WHERE application_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?", Long.class, key)).isZero();
    }

    private JsonNode create(String code, UUID key) throws Exception {
        keys.add(key);
        JsonNode response = read(call(post(PATH).with(csrf().asHeader()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("productCode", code))))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")));
        applicationIds.add(UUID.fromString(response.path("id").asString()));
        return response;
    }

    private ResultActions save(UUID id, long version, ObjectNode form) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("expectedVersion", version);
        body.set("form", form);
        return call(put(PATH + "/" + id + "/form").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(auth(USER))));
    }

    private JsonNode read(ResultActions response) throws Exception {
        return mapper.readTree(response.andReturn().getResponse().getContentAsString());
    }

    private static Authentication auth(CurrentUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(user, null, java.util.List.of());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
        }
    }

}
