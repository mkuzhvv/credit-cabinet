package com.creditcabinet.credit.credithistory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.creditcabinet.credit.decision.CreditDecisionPolicy;
import com.creditcabinet.credit.model.CreditHistoryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.type.LogicalType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoCreditHistoryProviderTest {

    private static final UUID COMPANY = UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef");
    private static final UUID OTHER = UUID.fromString("58dd3ac1-9622-4be8-92cb-4ed38371a32b");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-25T10:00:00Z");
    private final Clock clock = Clock.fixed(RECEIVED_AT, ZoneOffset.UTC);
    private final CreditHistoryReportValidator validator = new CreditHistoryReportValidator();
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();
    private final DefaultResourceLoader resources = new DefaultResourceLoader();

    @Test
    void readsMainCompanyProfileAndRoundTripsCompleteReport() {
        CreditHistoryReport report = provider().getCreditHistory(COMPANY);
        assertThat(report.schemaVersion()).isEqualTo("1");
        assertThat(report.companyId()).isEqualTo(COMPANY);
        assertThat(report.source()).isEqualTo("DEMO_BKI");
        assertThat(report.sourceVersion()).isEqualTo("demo-credit-history-v1");
        assertThat(report.reportId()).isNotNull();
        assertThat(report.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(report.profile().status()).isEqualTo(CreditHistoryStatus.AVAILABLE);
        assertThat(report.profile().totalOutstandingDebt()).isEqualByComparingTo("1200000.00");
        assertThat(report.profile().monthlyDebtPayment()).isEqualByComparingTo("80000.00");
        assertThat(report.profile().overdueDebtAmount()).isEqualByComparingTo("0.00");
        assertThat(report.profile().delinquencyPeriod().startDate()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(report.profile().delinquencyPeriod().endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(mapper.writeValueAsString(report)).contains("\"totalOutstandingDebt\":\"1200000.00\"");
        assertThat(mapper.readValue(mapper.writeValueAsString(report), CreditHistoryReport.class)).isEqualTo(report);
    }

    @Test
    void readsNoHistoryWithFourExplicitNulls() {
        CreditHistoryReport report = provider().getCreditHistory(OTHER);
        CreditHistoryProfile profile = report.profile();
        assertThat(profile.status()).isEqualTo(CreditHistoryStatus.NO_HISTORY);
        assertThat(profile.totalOutstandingDebt()).isNull();
        assertThat(profile.monthlyDebtPayment()).isNull();
        assertThat(profile.overdueDebtAmount()).isNull();
        assertThat(profile.maxDaysPastDueLast12Months()).isNull();
        assertThat(profile.delinquencyPeriod().months()).isEqualTo(12);
        assertThat(mapper.writeValueAsString(report)).contains("\"totalOutstandingDebt\":null", "\"monthlyDebtPayment\":null",
                "\"overdueDebtAmount\":null", "\"maxDaysPastDueLast12Months\":null");
    }

    @ParameterizedTest
    @CsvSource({"current-overdue.json,50000.00,15,true", "past-overdue.json,0.00,30,false"})
    void passesCurrentAndHistoricalOverdueToPolicyWithoutMixingThem(String file, String amount, int days, boolean declined) {
        CreditHistoryReport report = provider(resources.getResource("classpath:credit-history/" + file)).getCreditHistory(COMPANY);
        assertThat(report.profile().overdueDebtAmount()).isEqualByComparingTo(amount);
        assertThat(report.profile().maxDaysPastDueLast12Months()).isEqualTo(days);
        assertThat(new CreditDecisionPolicy().checkCurrentOverdueDebt(report.profile().status(), report.profile().overdueDebtAmount()).isPresent())
                .isEqualTo(declined);
    }

    @Test
    void keepsSourceDatesWhenReportIsFetchedLater() {
        CreditHistoryReport first = provider().getCreditHistory(COMPANY);
        Instant later = Instant.parse("2026-12-01T00:00:00Z");
        CreditHistoryReport second = new DemoCreditHistoryProvider(mapper, resources, Clock.fixed(later, ZoneOffset.UTC), validator, DemoCreditHistoryScenario.DEFAULT)
                .getCreditHistory(COMPANY);
        assertThat(second.profile()).isEqualTo(first.profile());
        assertThat(second.receivedAt()).isEqualTo(later);
        assertThat(second.reportId()).isNotEqualTo(first.reportId());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", textBlock = """
            ACTIVE_LOANS,AVAILABLE,1200000.00,80000.00,0.00,0
            REPAID_LOANS,AVAILABLE,0.00,0.00,0.00,0
            PAST_OVERDUE,AVAILABLE,1200000.00,80000.00,0.00,30
            CURRENT_OVERDUE,AVAILABLE,1200000.00,80000.00,50000.00,15
            NO_HISTORY,NO_HISTORY,null,null,null,null
            HIGH_DEBT_LOAD,AVAILABLE,8000000.00,650000.00,0.00,0
            """)
    void appliesSelectedProfileToExistingCompanies(DemoCreditHistoryScenario scenario, CreditHistoryStatus status,
            String total, String monthly, String overdue, Integer days) {
        for (UUID companyId : List.of(COMPANY, OTHER)) {
            CreditHistoryReport report = provider(scenario).getCreditHistory(companyId);
            assertThat(report.companyId()).isEqualTo(companyId);
            assertThat(report.source()).isEqualTo("DEMO_BKI");
            assertThat(report.sourceVersion()).startsWith("demo-credit-history-v1:");
            assertThat(report.profile().status()).isEqualTo(status);
            if (status == CreditHistoryStatus.NO_HISTORY) {
                assertThat(report.profile().totalOutstandingDebt()).isNull();
                assertThat(report.profile().monthlyDebtPayment()).isNull();
                assertThat(report.profile().overdueDebtAmount()).isNull();
            } else {
                assertThat(report.profile().totalOutstandingDebt()).isEqualByComparingTo(total);
                assertThat(report.profile().monthlyDebtPayment()).isEqualByComparingTo(monthly);
                assertThat(report.profile().overdueDebtAmount()).isEqualByComparingTo(overdue);
            }
            assertThat(report.profile().maxDaysPastDueLast12Months()).isEqualTo(days);
        }
    }

    @ParameterizedTest
    @CsvSource({"UNAVAILABLE,CREDIT_HISTORY_UNAVAILABLE", "TIMEOUT,CREDIT_HISTORY_TIMEOUT"})
    void producesConfiguredSourceFailureWithoutWaiting(DemoCreditHistoryScenario scenario, String code) {
        assertThatThrownBy(() -> provider(scenario).getCreditHistory(COMPANY))
                .isInstanceOfSatisfying(CreditHistoryException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test
    void doesNotCreateAnUnknownCompanyThroughScenarioSelection() {
        for (DemoCreditHistoryScenario scenario : List.of(DemoCreditHistoryScenario.ACTIVE_LOANS, DemoCreditHistoryScenario.TIMEOUT)) {
            assertThatThrownBy(() -> provider(scenario).getCreditHistory(UUID.randomUUID()))
                    .isInstanceOfSatisfying(CreditHistoryException.class, ex -> assertThat(ex.getCode()).isEqualTo("CREDIT_HISTORY_UNAVAILABLE"));
        }
    }

    @Test
    void commonValidatorAcceptsNormalizedReportFromAnotherSource() {
        CreditHistoryReport demo = provider().getCreditHistory(COMPANY);
        CreditHistoryReport external = new CreditHistoryReport(demo.schemaVersion(), COMPANY, "EXTERNAL_SOURCE", "external-source-v1",
                demo.reportId(), demo.receivedAt(), demo.profile());
        validator.validate(COMPANY, external);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            schemaVersion | "2"
            schemaVersion | 1
            source | "OTHER"
            sourceVersion | "  "
            companyId | "58dd3ac1-9622-4be8-92cb-4ed38371a32b"
            companyId | null
            profile | null
            profile.status | null
            profile.status | "UNAVAILABLE"
            profile.asOfDate | null
            profile.asOfDate | "2026-10-26"
            profile.asOfDate | "bad-date"
            profile.delinquencyPeriod | null
            profile.delinquencyPeriod.months | 3
            profile.delinquencyPeriod.startDate | "2025-10-02"
            profile.delinquencyPeriod.endDate | "2026-10-01"
            profile.totalOutstandingDebt | null
            profile.monthlyDebtPayment | null
            profile.overdueDebtAmount | null
            profile.maxDaysPastDueLast12Months | null
            profile.totalOutstandingDebt | "-0.01"
            profile.totalOutstandingDebt | "1000000000000.00"
            profile.monthlyDebtPayment | "12.001"
            profile.monthlyDebtPayment | 12.00
            profile.overdueDebtAmount | "1200000.01"
            profile.maxDaysPastDueLast12Months | -1
            profile.maxDaysPastDueLast12Months | 1.5
            """)
    void rejectsInvalidSourceData(String path, String value) throws IOException {
        ObjectNode document = document(COMPANY);
        set(document, path, value);
        assertInvalid(provider(new ByteArrayResource(mapper.writeValueAsBytes(document))));
    }

    @Test
    void rejectsMissingKeysAndMalformedDocuments() throws IOException {
        ObjectNode missing = document(COMPANY);
        ((ObjectNode) missing.path("profile")).remove("monthlyDebtPayment");
        assertInvalid(provider(new ByteArrayResource(mapper.writeValueAsBytes(missing))));
        ObjectNode unknown = document(COMPANY);
        unknown.put("unknown", true);
        assertInvalid(provider(new ByteArrayResource(mapper.writeValueAsBytes(unknown))));
        for (String invalid : List.of("{", "null", mapper.writeValueAsString(document(COMPANY)) + " {}")) {
            assertInvalid(provider(new ByteArrayResource(invalid.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    void rejectsValuesAndMissingKeysInNoHistory() throws IOException {
        for (String field : List.of("totalOutstandingDebt", "monthlyDebtPayment", "overdueDebtAmount", "maxDaysPastDueLast12Months")) {
            ObjectNode value = document(OTHER);
            ((ObjectNode) value.path("profile")).set(field, mapper.readTree(field.equals("maxDaysPastDueLast12Months") ? "0" : "\"0.00\""));
            assertThatThrownBy(() -> provider(new ByteArrayResource(mapper.writeValueAsBytes(value))).getCreditHistory(OTHER))
                    .isInstanceOfSatisfying(CreditHistoryException.class, ex -> assertThat(ex.getCode()).isEqualTo("CREDIT_HISTORY_INVALID_RESPONSE"));
            ObjectNode missing = document(OTHER);
            ((ObjectNode) missing.path("profile")).remove(field);
            assertThatThrownBy(() -> provider(new ByteArrayResource(mapper.writeValueAsBytes(missing))).getCreditHistory(OTHER))
                    .isInstanceOf(CreditHistoryException.class);
        }
    }

    @Test
    void allowsLongPastOverdueWithNoCurrentDebt() throws IOException {
        ObjectNode document = document(COMPANY);
        for (String field : List.of("totalOutstandingDebt", "monthlyDebtPayment", "overdueDebtAmount")) {
            set(document, "profile." + field, "\"0.00\"");
        }
        set(document, "profile.maxDaysPastDueLast12Months", "500");
        CreditHistoryReport report = provider(new ByteArrayResource(mapper.writeValueAsBytes(document))).getCreditHistory(COMPANY);
        assertThat(report.profile().maxDaysPastDueLast12Months()).isEqualTo(500);
    }

    @Test
    void validatesReportMetadataAndUsesUtcForDateComparison() throws IOException {
        CreditHistoryReport good = provider().getCreditHistory(COMPANY);
        for (CreditHistoryReport bad : List.of(
                new CreditHistoryReport("1", COMPANY, "DEMO_BKI", null, good.reportId(), RECEIVED_AT, good.profile()),
                new CreditHistoryReport("1", COMPANY, "DEMO_BKI", "demo-v1", null, RECEIVED_AT, good.profile()),
                new CreditHistoryReport("1", COMPANY, "DEMO_BKI", "demo-v1", good.reportId(), null, good.profile()),
                new CreditHistoryReport("1", COMPANY, null, "source-v1", good.reportId(), RECEIVED_AT, good.profile()),
                new CreditHistoryReport("1", COMPANY, "  ", "source-v1", good.reportId(), RECEIVED_AT, good.profile()))) {
            assertThatThrownBy(() -> validator.validate(COMPANY, bad)).isInstanceOf(CreditHistoryException.class);
        }
        ObjectNode future = document(COMPANY);
        set(future, "profile.asOfDate", "\"2026-10-25\"");
        CreditHistoryProvider provider = new DemoCreditHistoryProvider(mapper, loader(new ByteArrayResource(mapper.writeValueAsBytes(future))),
                Clock.fixed(Instant.parse("2026-10-24T23:30:00Z"), ZoneOffset.ofHours(9)), validator, DemoCreditHistoryScenario.DEFAULT);
        assertInvalid(provider);
    }

    @Test
    void rejectsInvalidTypedMoneyBeforePersistence() {
        CreditHistoryReport good = provider().getCreditHistory(COMPANY);
        CreditHistoryProfile profile = good.profile();
        for (String money : List.of("-0.01", "0.001", "1000000000000.00")) {
            CreditHistoryProfile bad = new CreditHistoryProfile(profile.status(), profile.asOfDate(), profile.delinquencyPeriod(),
                    profile.totalOutstandingDebt(), new BigDecimal(money), profile.overdueDebtAmount(), profile.maxDaysPastDueLast12Months());
            CreditHistoryReport report = new CreditHistoryReport(good.schemaVersion(), COMPANY, good.source(), good.sourceVersion(),
                    good.reportId(), good.receivedAt(), bad);
            assertThatThrownBy(() -> validator.validate(COMPANY, report)).isInstanceOf(CreditHistoryException.class);
        }
    }

    @Test
    void distinguishesMissingProfileIoFailureAndTimeout() {
        assertThatThrownBy(() -> provider().getCreditHistory(UUID.randomUUID()))
                .isInstanceOfSatisfying(CreditHistoryException.class, ex -> assertThat(ex.getCode()).isEqualTo("CREDIT_HISTORY_UNAVAILABLE"));
        assertSourceFailure(new IOException("controlled read failure"), "CREDIT_HISTORY_UNAVAILABLE");
        assertSourceFailure(new SocketTimeoutException("controlled timeout"), "CREDIT_HISTORY_TIMEOUT");
    }

    private DemoCreditHistoryProvider provider() {
        return provider(DemoCreditHistoryScenario.DEFAULT);
    }

    private DemoCreditHistoryProvider provider(DemoCreditHistoryScenario scenario) {
        return new DemoCreditHistoryProvider(mapper, resources, clock, validator, scenario);
    }

    private DemoCreditHistoryProvider provider(Resource resource) {
        return new DemoCreditHistoryProvider(mapper, loader(resource), clock, validator, DemoCreditHistoryScenario.DEFAULT);
    }

    private DefaultResourceLoader loader(Resource resource) {
        return new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resource;
            }
        };
    }

    private ObjectNode document(UUID companyId) throws IOException {
        return (ObjectNode) mapper.readTree(resources.getResource("classpath:demo/credit-history/" + companyId + ".json").getContentAsByteArray());
    }

    private void set(ObjectNode document, String path, String json) {
        String[] names = path.split("\\.");
        ObjectNode object = document;
        for (int index = 0; index < names.length - 1; index++) {
            object = (ObjectNode) object.path(names[index]);
        }
        object.set(names[names.length - 1], mapper.readTree(json));
    }

    private void assertInvalid(CreditHistoryProvider provider) {
        assertThatThrownBy(() -> provider.getCreditHistory(COMPANY)).isInstanceOfSatisfying(CreditHistoryException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("CREDIT_HISTORY_INVALID_RESPONSE"));
    }

    private void assertSourceFailure(IOException failure, String expectedCode) {
        Resource resource = new AbstractResource() {
            @Override
            public String getDescription() {
                return "controlled credit history source";
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw failure;
            }
        };
        assertThatThrownBy(() -> provider(resource).getCreditHistory(COMPANY)).isInstanceOfSatisfying(CreditHistoryException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(expectedCode)).hasCause(failure);
    }
}
