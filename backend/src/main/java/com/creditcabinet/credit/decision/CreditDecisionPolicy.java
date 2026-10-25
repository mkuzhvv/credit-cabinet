package com.creditcabinet.credit.decision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.creditcabinet.catalog.model.ProductTerms;
import com.creditcabinet.common.json.MoneyJson;
import com.creditcabinet.credit.model.ApprovedTerms;
import com.creditcabinet.credit.model.CreditDecision;
import com.creditcabinet.credit.model.CreditHistoryStatus;
import com.creditcabinet.credit.model.DecisionReason;
import com.creditcabinet.credit.model.ReportingPeriod;
import org.springframework.stereotype.Component;

@Component
public class CreditDecisionPolicy {

    public int businessAgeMonths(LocalDate registrationDate, LocalDate asOfDate) {
        return (int) Math.max(0, ChronoUnit.MONTHS.between(registrationDate, asOfDate));
    }

    public Optional<DecisionReason> checkCompany(CompanyFacts company, LocalDate asOfDate, ProductTerms terms) {
        if (businessAgeMonths(company.registrationDate(), asOfDate) < terms.minBusinessAgeMonths()) {
            return Optional.of(new DecisionReason("BUSINESS_TOO_YOUNG", "Возраст бизнеса меньше установленного для продукта"));
        }
        if (terms.requiresSettlementAccount() && !company.hasSettlementAccount()) {
            return Optional.of(new DecisionReason("SETTLEMENT_ACCOUNT_REQUIRED", "Для продукта требуется расчётный счёт"));
        }
        if (terms.requiresRegularInflow() && !company.hasRegularInflowLast3Months()) {
            return Optional.of(new DecisionReason("REGULAR_INFLOW_REQUIRED", "Для продукта требуются регулярные поступления за последние три месяца"));
        }
        return Optional.empty();
    }

    public Optional<DecisionReason> checkCurrentOverdueDebt(CreditHistoryStatus status, BigDecimal overdueDebtAmount) {
        Objects.requireNonNull(status);
        if (status == CreditHistoryStatus.NO_HISTORY) {
            if (overdueDebtAmount != null) {
                throw new IllegalArgumentException("При отсутствии истории сумма просрочки должна быть null");
            }
            return Optional.empty();
        }
        if (overdueDebtAmount == null || overdueDebtAmount.signum() < 0) {
            throw new IllegalArgumentException("Полученная история должна содержать неотрицательную сумму текущей просрочки");
        }
        if (overdueDebtAmount.signum() > 0) {
            return Optional.of(new DecisionReason("CURRENT_OVERDUE_DEBT", "Обнаружена текущая просроченная задолженность"));
        }
        return Optional.empty();
    }

    public Optional<ClarificationRequirement> requiredInflowConfirmation(SubmissionContext input, List<InflowConfirmation> accepted) {
        BigDecimal inflow = Objects.requireNonNull(input.form().averageMonthlyInflow());
        if (inflow.signum() < 0) {
            throw new IllegalArgumentException("Поступления не могут быть отрицательными");
        }
        if (inflow.signum() > 0) {
            return Optional.empty();
        }
        boolean confirmed = accepted.stream().anyMatch(confirmation -> confirmation.field().equals("form.averageMonthlyInflow")
                && confirmation.value().compareTo(inflow) == 0 && confirmation.period().equals(input.reportingPeriod()));
        if (confirmed) {
            return Optional.empty();
        }
        return Optional.of(new ClarificationRequirement("form.averageMonthlyInflow", "ZERO_INFLOW_CONFIRMATION",
                "Подтвердите нулевые среднемесячные поступления за указанный период", new BigDecimal("0.00"), input.reportingPeriod()));
    }

    public CreditDecision decline(DecisionReason reason, Instant decidedAt) {
        return new CreditDecision(CreditDecision.Type.DECLINED, decidedAt, List.of(reason), null);
    }

    public CreditDecision decideByRisk(SubmissionContext input, PolicyParameters parameters, RiskEstimate estimate, Instant decidedAt) {
        BigDecimal risk = estimate.riskScore();
        if (estimate.featureSchemaVersion() != parameters.featureSchemaVersion()
                || !parameters.expectedModelVersion().equals(estimate.modelVersion())) {
            throw new InvalidRiskEstimateException("Версия схемы или модели не соответствует закреплённой политике");
        }
        if (risk == null || risk.signum() < 0 || risk.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidRiskEstimateException("Оценка риска должна присутствовать и находиться в диапазоне [0, 1]");
        }
        if (risk.compareTo(parameters.riskCutoff()) >= 0) {
            return decline(new DecisionReason("RISK_THRESHOLD_EXCEEDED", "Оценка риска достигла или превысила установленный порог"), decidedAt);
        }
        ProductTerms terms = input.terms();
        ApprovedTerms approved = new ApprovedTerms(input.form().amount(), terms.currency(), input.form().termMonths(),
                terms.annualRatePercent(), terms.repaymentDescription(), terms.financingType(), terms.repaymentType(),
                input.asOfDate().plusMonths(input.form().termMonths()), input.form().trancheTermMonths(),
                terms.overdraftRules() == null ? null : terms.overdraftRules().maxDebtPeriodDays());
        return new CreditDecision(CreditDecision.Type.APPROVED, decidedAt, List.of(), approved);
    }

    public record CompanyFacts(LocalDate registrationDate, boolean hasSettlementAccount, boolean hasRegularInflowLast3Months) {
        public CompanyFacts {
            Objects.requireNonNull(registrationDate);
        }
    }

    public record InflowConfirmation(String field, BigDecimal value, ReportingPeriod period) {
        public InflowConfirmation {
            Objects.requireNonNull(field);
            Objects.requireNonNull(value);
            Objects.requireNonNull(period);
        }
    }

    public record ClarificationRequirement(String field, String code, String message, @MoneyJson BigDecimal observedValue,
            ReportingPeriod reportingPeriod) {
    }
}
