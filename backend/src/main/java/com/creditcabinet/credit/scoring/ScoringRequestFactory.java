package com.creditcabinet.credit.scoring;

import java.util.UUID;

import com.creditcabinet.credit.credithistory.CreditHistoryReport;
import com.creditcabinet.credit.credithistory.CreditHistoryReportValidator;
import com.creditcabinet.credit.decision.CreditDecisionPolicy;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.CompanyFacts;
import com.creditcabinet.credit.decision.SubmissionContext;
import com.creditcabinet.credit.decision.SubmissionValidator;
import com.creditcabinet.credit.model.CreditHistoryStatus;
import com.creditcabinet.credit.scoring.ScoringFeatures.GovernmentContractFeatures;
import org.springframework.stereotype.Component;

@Component
public class ScoringRequestFactory {

    private final SubmissionValidator forms;
    private final CreditHistoryReportValidator histories;
    private final CreditDecisionPolicy policy;

    public ScoringRequestFactory(SubmissionValidator forms, CreditHistoryReportValidator histories, CreditDecisionPolicy policy) {
        this.forms = forms;
        this.histories = histories;
        this.policy = policy;
    }

    public ScoringRequest create(UUID assessmentId, UUID applicationId, UUID companyId,
            SubmissionContext input, CompanyFacts company, CreditHistoryReport history) {
        if (!forms.validate(input).isEmpty()) {
            throw invalid("Сохранённые данные не готовы для расчёта");
        }
        histories.validate(companyId, history);
        if (history.profile().asOfDate().isAfter(input.asOfDate())) {
            throw invalid("Дата кредитного профиля позже даты принятия попытки");
        }
        if (history.profile().status() == CreditHistoryStatus.AVAILABLE && history.profile().overdueDebtAmount().signum() > 0) {
            throw invalid("Текущая просрочка должна быть проверена до вызова скоринга");
        }
        var form = input.form();
        var contract = form.governmentContract();
        GovernmentContractFeatures government = contract == null ? null : new GovernmentContractFeatures(contract.contractAmount(),
                contract.advanceAmount(), contract.executionEndDate(), contract.finalPaymentDate(), input.asOfDate().plusMonths(form.termMonths()));
        ScoringFeatures features = new ScoringFeatures(input.productCode(), input.terms().currency(), form.amount(), form.termMonths(),
                form.averageMonthlyInflow(), form.averageMonthlyOutflow(), input.reportingPeriod(), input.asOfDate(),
                policy.businessAgeMonths(company.registrationDate(), input.asOfDate()), history.profile(),
                form.projectBudget(), form.trancheTermMonths(), government, form.cashBalance());
        return new ScoringRequest(assessmentId, applicationId, "4", features);
    }

    private ScoringException invalid(String message) {
        return new ScoringException(ScoringException.Code.SCORING_INVALID_RESPONSE, message);
    }
}
