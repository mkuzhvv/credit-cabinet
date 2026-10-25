package com.creditcabinet.credit.service;

import java.time.Clock;

import com.creditcabinet.credit.credithistory.CreditHistoryException;
import com.creditcabinet.credit.credithistory.CreditHistoryService;
import com.creditcabinet.credit.decision.CreditDecisionPolicy;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.CompanyFacts;
import com.creditcabinet.credit.decision.InvalidRiskEstimateException;
import com.creditcabinet.credit.processing.AssessmentClaim;
import com.creditcabinet.credit.processing.AssessmentClaimLostException;
import com.creditcabinet.credit.processing.ProcessingProperties;
import com.creditcabinet.credit.scoring.RiskScoringClient;
import com.creditcabinet.credit.scoring.ScoringException;
import com.creditcabinet.credit.scoring.ScoringProperties;
import com.creditcabinet.credit.scoring.ScoringRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ProcessApplicationService.class);
    private final AssessmentStateService state;
    private final CreditHistoryService histories;
    private final CreditDecisionPolicy policy;
    private final ScoringRequestFactory features;
    private final RiskScoringClient scoring;
    private final ScoringProperties scoringProperties;
    private final Clock clock;

    public ProcessApplicationService(AssessmentStateService state, CreditHistoryService histories, CreditDecisionPolicy policy,
            ScoringRequestFactory features, RiskScoringClient scoring, ScoringProperties scoringProperties,
            ProcessingProperties properties, Clock clock) {
        if (properties.leaseDuration().compareTo(properties.creditHistoryTimeout().plus(scoringProperties.timeout()).plusSeconds(5)) <= 0) {
            throw new IllegalArgumentException("Срок захвата должен превышать бюджеты кредитного источника и скоринга с запасом 5 секунд");
        }
        this.state = state;
        this.histories = histories;
        this.policy = policy;
        this.features = features;
        this.scoring = scoring;
        this.scoringProperties = scoringProperties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void process(AssessmentClaim claim) {
        try {
            execute(claim);
        } catch (AssessmentClaimLostException ex) {
            log.info("Попытка {} больше не принадлежит запуску {}", claim.assessmentId(), claim.token());
        } catch (CreditHistoryException ex) {
            hold(claim, ex.getCode(), "Не удалось получить корректную кредитную историю. Повторите проверку");
        } catch (ScoringException ex) {
            hold(claim, ex.getCode(), "Сервис расчёта не смог завершить проверку. Повторите рассмотрение");
        } catch (InvalidRiskEstimateException ex) {
            hold(claim, "SCORING_INVALID_RESPONSE", "Результат расчёта не соответствует сохранённой политике");
        } catch (RuntimeException ex) {
            log.error("Ошибка обработки попытки {}", claim.assessmentId(), ex);
            hold(claim, "PROCESSING_INTERRUPTED", "Не удалось завершить проверку. Повторите рассмотрение");
        }
    }

    private void execute(AssessmentClaim claim) {
        var current = state.load(claim);
        if (current.isEmpty()) {
            return;
        }
        var work = current.get();
        var input = work.input();
        var company = new CompanyFacts(input.company().registrationDate(), input.company().hasSettlementAccount(),
                input.company().hasRegularInflowLast3Months());
        var companyFailure = policy.checkCompany(company, input.asOfDate(), input.product().terms());
        if (companyFailure.isPresent()) {
            state.finishDecision(claim, policy.decline(companyFailure.get(), clock.instant()), null);
            return;
        }
        var report = work.creditHistory();
        if (report == null) {
            report = histories.getReport(input.company().id(), input.asOfDate());
            report = state.storeHistory(claim, report);
        } else {
            histories.validate(input.company().id(), input.asOfDate(), report);
        }
        var overdue = policy.checkCurrentOverdueDebt(report.profile().status(), report.profile().overdueDebtAmount());
        if (overdue.isPresent()) {
            state.finishDecision(claim, policy.decline(overdue.get(), clock.instant()), null);
            return;
        }
        var question = policy.requiredInflowConfirmation(input.submissionContext(), input.confirmations());
        if (question.isPresent()) {
            state.requestInformation(claim, question.get());
            return;
        }
        if (!work.policy().expectedModelVersion().equals(scoringProperties.expectedModelVersion())) {
            throw new ScoringException(ScoringException.Code.SCORING_INVALID_RESPONSE,
                    "Сохранённая версия алгоритма не совпадает с подключённым сервисом");
        }
        var request = features.create(claim.assessmentId(), claim.applicationId(), input.company().id(), input.submissionContext(), company, report);
        var risk = scoring.assess(request, work.requestId());
        var decision = policy.decideByRisk(input.submissionContext(), work.policy(), risk, clock.instant());
        state.finishDecision(claim, decision, risk);
    }

    private void hold(AssessmentClaim claim, String code, String message) {
        try {
            state.technicalHold(claim, code, message);
        } catch (AssessmentClaimLostException ex) {
            log.info("Технический исход устаревшего запуска {} не сохранён", claim.token());
        }
    }
}
