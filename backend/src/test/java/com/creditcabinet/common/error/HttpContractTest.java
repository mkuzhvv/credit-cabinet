package com.creditcabinet.common.error;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import com.creditcabinet.common.json.MoneyJson;
import com.creditcabinet.config.JsonConfig;
import com.creditcabinet.config.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HttpContractTest.ProbeController.class)
@Import({JsonConfig.class, ApiProblemFactory.class, RequestIdFilter.class,
        HttpContractTest.ProbeController.class, HttpContractTest.TestSecurity.class})
@TestExecutionListeners({ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class})
class HttpContractTest {

    private static final String PATH = "/api/v1/_contract";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper mapper;

    @ParameterizedTest
    @MethodSource("malformedRequests")
    void rejectsMalformedRequest(String body) throws Exception {
        expectProblem(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)), 400, "MALFORMED_REQUEST");
    }

    static Stream<String> malformedRequests() {
        return Stream.of(
                "{",
                body("\"12,00\""),
                body("\"12.001\""),
                body("12.00"),
                body("\"1000000000000.00\""),
                body("\"-1.00\""),
                body("\"12.00\"").replace("\"text\":\"ok\"", "\"text\":\"ok\",\"unknown\":true"),
                body("\"12.00\"").replace("\"text\":\"ok\"", "\"text\":123"),
                body("\"12.00\"").replace("\"termMonths\":12", "\"termMonths\":12.5"),
                body("\"12.00\"") + " {}");
    }

    @Test
    void reportsNestedValidationError() throws Exception {
        expectProblem(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(body("\"12.00\"").replace("\"ok\"", "\"   \""))), 422, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[0].field").value("form.text"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void reportsMoneyFieldPath() throws Exception {
        expectProblem(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(body("\"12.001\""))), 400, "MALFORMED_REQUEST")
                .andExpect(jsonPath("$.errors[0].field").value("form.amount"));
    }

    @Test
    void rejectsInvalidUuid() throws Exception {
        expectProblem(mvc.perform(get(PATH + "/ids/not-a-uuid")), 400, "MALFORMED_REQUEST");
    }

    @Test
    void validatesQueryParameters() throws Exception {
        expectProblem(mvc.perform(get(PATH + "/limit").param("limit", "0")), 422, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "999999999999.99"})
    void preservesMoneyAndAddsRequestId(String money) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("\"" + money + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.amount").value(money))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(result -> assertThat(UUID.fromString(result.getResponse().getHeader(RequestIdFilter.HEADER))).isNotNull());
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void writesTwoDecimalPlacesWithoutRounding() {
        assertThat(mapper.writeValueAsString(new MoneyValue(new BigDecimal("42")))).isEqualTo("{\"amount\":\"42.00\"}");
        assertThatThrownBy(() -> mapper.writeValueAsString(new MoneyValue(new BigDecimal("42.001"))))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void hidesInternalFailureDetails() throws Exception {
        expectProblem(mvc.perform(get(PATH + "/failure")), 500, "INTERNAL_ERROR")
                .andExpect(content().string(not(containsString("internal-diagnostic-only"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    private ResultActions expectProblem(ResultActions result, int httpStatus, String code) throws Exception {
        return result.andExpect(status().is(httpStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").isNotEmpty())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(response -> {
                    String id = response.getResponse().getHeader(RequestIdFilter.HEADER);
                    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
                    jsonPath("$.requestId").value(id).match(response);
                    assertThat(MDC.get("requestId")).isNull();
                });
    }

    private static String body(String amount) {
        return "{\"form\":{\"amount\":" + amount + ",\"text\":\"ok\",\"termMonths\":12}}";
    }

    record ProbeInput(@NotNull @Valid ProbeForm form) {
    }

    record ProbeForm(@MoneyJson @NotNull BigDecimal amount, @NotBlank String text, @NotNull Integer termMonths) {
    }

    record MoneyValue(@MoneyJson BigDecimal amount) {
    }

    @RestController
    @RequestMapping(PATH)
    static class ProbeController {

        @PostMapping
        ProbeInput echo(@Valid @RequestBody ProbeInput input) {
            return input;
        }

        @GetMapping("/ids/{id}")
        UUID id(@PathVariable UUID id) {
            return id;
        }

        @GetMapping("/limit")
        int limit(@RequestParam @Min(1) int limit) {
            return limit;
        }

        @GetMapping("/failure")
        void failure() {
            throw new IllegalStateException("internal-diagnostic-only");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSecurity {

        @Bean
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher(PATH + "/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }
}
