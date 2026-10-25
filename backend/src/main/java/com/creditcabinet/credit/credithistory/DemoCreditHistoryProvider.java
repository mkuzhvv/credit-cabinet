package com.creditcabinet.credit.credithistory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DemoCreditHistoryProvider implements CreditHistoryProvider {

    private final JsonMapper mapper;
    private final ResourceLoader resources;
    private final Clock clock;
    private final CreditHistoryReportValidator validator;
    private final DemoCreditHistoryScenario scenario;

    public DemoCreditHistoryProvider(JsonMapper mapper, ResourceLoader resources, Clock clock, CreditHistoryReportValidator validator,
            @Value("${demo.credit-history.scenario:DEFAULT}") DemoCreditHistoryScenario scenario) {
        this.mapper = mapper;
        this.resources = resources;
        this.clock = clock;
        this.validator = validator;
        this.scenario = Objects.requireNonNull(scenario);
    }

    @Override
    public CreditHistoryReport getCreditHistory(UUID companyId) {
        Objects.requireNonNull(companyId);
        try {
            byte[] json = resources.getResource("classpath:demo/credit-history/" + companyId + ".json").getContentAsByteArray();
            DemoProfile data = mapper.readValue(json, DemoProfile.class);
            if (data == null) {
                throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_INVALID_RESPONSE, "Демопрофиль отсутствует в документе");
            }
            if (!"DEMO_BKI".equals(data.source())) {
                throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_INVALID_RESPONSE, "Демопровайдер ожидает источник DEMO_BKI");
            }
            if (scenario == DemoCreditHistoryScenario.UNAVAILABLE) {
                throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_UNAVAILABLE, "Демонстрационный сценарий недоступности кредитного источника");
            }
            if (scenario == DemoCreditHistoryScenario.TIMEOUT) {
                throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_TIMEOUT, "Демонстрационный сценарий таймаута кредитного источника");
            }
            CreditHistoryProfile profile = data.profile();
            String sourceVersion = data.sourceVersion();
            if (scenario != DemoCreditHistoryScenario.DEFAULT) {
                String name = scenario.name().toLowerCase(Locale.ROOT).replace('_', '-');
                byte[] scenarioJson = resources.getResource("classpath:demo/credit-history/scenarios/" + name + ".json").getContentAsByteArray();
                profile = mapper.readValue(scenarioJson, CreditHistoryProfile.class);
                if (sourceVersion != null && !sourceVersion.isBlank()) {
                    sourceVersion += ":" + name;
                }
            }
            CreditHistoryReport report = new CreditHistoryReport(data.schemaVersion(), data.companyId(), data.source(),
                    sourceVersion, UUID.randomUUID(), clock.instant(), profile);
            validator.validate(companyId, report);
            return report;
        } catch (SocketTimeoutException ex) {
            throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_TIMEOUT, "Истёк срок получения кредитного профиля", ex);
        } catch (IOException ex) {
            throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_UNAVAILABLE, "Демопрофиль компании недоступен", ex);
        } catch (JacksonException ex) {
            throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_INVALID_RESPONSE, "Некорректный формат демопрофиля компании", ex);
        }
    }

    private record DemoProfile(
            @JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) UUID companyId,
            @JsonProperty(required = true) String source,
            @JsonProperty(required = true) String sourceVersion,
            @JsonProperty(required = true) CreditHistoryProfile profile) {
    }
}
