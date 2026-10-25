package com.creditcabinet.credit.decision;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import com.creditcabinet.credit.decision.CreditDecisionPolicy.CompanyFacts;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.InflowConfirmation;
import com.creditcabinet.credit.model.CreditDecision;
import com.creditcabinet.credit.model.CreditHistoryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.creditcabinet.credit.decision.DecisionTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditDecisionPolicyTest {

    private static final Instant DECIDED_AT = Instant.parse("2026-10-25T10:00:00Z");
    private static final PolicyParameters PARAMETERS = new PolicyParameters("test-policy-v4", 4, "test-model-v4", money("0.50"));
    private final CreditDecisionPolicy policy = new CreditDecisionPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE"})
    void checksBusinessAgeBoundary(String code) {
        var terms = terms(code);
        LocalDate registration = DATE.minusMonths(terms.minBusinessAgeMonths());
        assertThat(policy.checkCompany(new CompanyFacts(registration, true, true), DATE, terms)).isEmpty();
        assertThat(policy.checkCompany(new CompanyFacts(registration.minusDays(1), true, true), DATE, terms)).isEmpty();
        assertThat(policy.checkCompany(new CompanyFacts(registration.plusDays(1), true, true), DATE, terms))
                .get().extracting(reason -> reason.code()).isEqualTo("BUSINESS_TOO_YOUNG");
    }

    @Test
    void usesSubmissionDateForAgeAndChecksBankRequirementsInOrder() {
        var overdraft = terms("OVERDRAFT");
        CompanyFacts company = new CompanyFacts(DATE.minusMonths(6).plusDays(1), false, false);
        assertThat(policy.checkCompany(company, DATE, overdraft)).get().extracting(reason -> reason.code()).isEqualTo("BUSINESS_TOO_YOUNG");
        assertThat(policy.checkCompany(company, DATE.plusMonths(1), overdraft)).get().extracting(reason -> reason.code()).isEqualTo("SETTLEMENT_ACCOUNT_REQUIRED");
        assertThat(policy.checkCompany(new CompanyFacts(DATE.minusYears(2), true, false), DATE, overdraft))
                .get().extracting(reason -> reason.code()).isEqualTo("REGULAR_INFLOW_REQUIRED");
        assertThat(policy.checkCompany(new CompanyFacts(DATE.minusYears(2), false, false), DATE, terms("WORKING_CAPITAL"))).isEmpty();
    }

    @Test
    void checksCurrentOverdueWithoutInventingDataForNoHistory() {
        assertThat(policy.checkCurrentOverdueDebt(CreditHistoryStatus.AVAILABLE, money("0.00"))).isEmpty();
        assertThat(policy.checkCurrentOverdueDebt(CreditHistoryStatus.AVAILABLE, money("0.01")))
                .get().extracting(reason -> reason.code()).isEqualTo("CURRENT_OVERDUE_DEBT");
        assertThat(policy.checkCurrentOverdueDebt(CreditHistoryStatus.NO_HISTORY, null)).isEmpty();
        assertThatThrownBy(() -> policy.checkCurrentOverdueDebt(CreditHistoryStatus.AVAILABLE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.checkCurrentOverdueDebt(CreditHistoryStatus.NO_HISTORY, money("0.00"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesConfirmationByFieldValueAndPeriod() {
        SubmissionContext input = withForm(context("WORKING_CAPITAL"), inflow(context("WORKING_CAPITAL").form(), "0.00"));
        assertThat(policy.requiredInflowConfirmation(input, List.of())).get().extracting(rule -> rule.code()).isEqualTo("ZERO_INFLOW_CONFIRMATION");
        assertThat(policy.requiredInflowConfirmation(input, List.of(new InflowConfirmation("form.averageMonthlyInflow", money("0"), input.reportingPeriod())))).isEmpty();
        for (InflowConfirmation wrong : List.of(
                new InflowConfirmation("form.averageMonthlyOutflow", money("0.00"), input.reportingPeriod()),
                new InflowConfirmation("form.averageMonthlyInflow", money("1.00"), input.reportingPeriod()),
                new InflowConfirmation("form.averageMonthlyInflow", money("0.00"), context("OVERDRAFT").reportingPeriod()))) {
            assertThat(policy.requiredInflowConfirmation(input, List.of(wrong))).isPresent();
        }
        assertThat(policy.requiredInflowConfirmation(context("WORKING_CAPITAL"), List.of())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0,APPROVED", "1,DECLINED"})
    void appliesBinaryResultAtFixedCutoff(String risk, CreditDecision.Type expected) {
        CreditDecision result = policy.decideByRisk(context("WORKING_CAPITAL"), PARAMETERS, new RiskEstimate(4, "test-model-v4", money(risk)), DECIDED_AT);
        assertThat(result.type()).isEqualTo(expected);
        assertThat(result.decidedAt()).isEqualTo(DECIDED_AT);
        if (expected == CreditDecision.Type.DECLINED) {
            assertThat(result.reasons()).extracting(reason -> reason.code()).containsExactly("RISK_THRESHOLD_EXCEEDED");
            assertThat(result.terms()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE"})
    void buildsApprovalFromReviewedTerms(String code) {
        SubmissionContext input = context(code);
        var result = policy.decideByRisk(input, PARAMETERS, new RiskEstimate(4, "test-model-v4", money("0")), DECIDED_AT);
        assertThat(result.terms().amount()).isEqualByComparingTo(input.form().amount());
        assertThat(result.terms().annualRatePercent()).isEqualByComparingTo(input.terms().annualRatePercent());
        assertThat(result.terms().financingType()).isEqualTo(input.terms().financingType());
        assertThat(result.terms().repaymentType()).isEqualTo(input.terms().repaymentType());
        assertThat(result.terms().trancheTermMonths()).isEqualTo(input.form().trancheTermMonths());
        assertThat(result.terms().maxDebtPeriodDays()).isEqualTo(code.equals("OVERDRAFT") ? 30 : null);
    }

    @Test
    void calculatesCalendarEndFromSubmissionDateAndCreatesReasonedDecline() {
        SubmissionContext original = context("WORKING_CAPITAL");
        SubmissionContext later = new SubmissionContext(original.productCode(), original.terms(), original.form(),
                original.calculationDate(), original.reportingPeriod(), LocalDate.of(2027, 1, 31));
        var approval = policy.decideByRisk(later, PARAMETERS, new RiskEstimate(4, "test-model-v4", money("0")), DECIDED_AT);
        assertThat(approval.terms().plannedEndDate()).isEqualTo(LocalDate.of(2027, 4, 30));
        var reason = policy.checkCompany(new CompanyFacts(DATE, true, true), DATE, original.terms()).orElseThrow();
        CreditDecision decline = policy.decline(reason, DECIDED_AT);
        assertThat(decline.type()).isEqualTo(CreditDecision.Type.DECLINED);
        assertThat(decline.reasons()).containsExactly(reason);
        assertThat(decline.terms()).isNull();
    }

    @Test
    void invalidRiskOrVersionIsTechnicalFailureInsteadOfDecision() {
        for (String risk : Arrays.asList(null, "-0.01", "0.10", "0.50", "1.01")) {
            RiskEstimate estimate = new RiskEstimate(4, "test-model-v4", risk == null ? null : money(risk));
            assertThatThrownBy(() -> policy.decideByRisk(context("WORKING_CAPITAL"), PARAMETERS, estimate, DECIDED_AT))
                    .isInstanceOf(InvalidRiskEstimateException.class);
        }
        assertThatThrownBy(() -> policy.decideByRisk(context("WORKING_CAPITAL"), PARAMETERS,
                new RiskEstimate(3, "test-model-v4", money("0")), DECIDED_AT)).isInstanceOf(InvalidRiskEstimateException.class);
        assertThatThrownBy(() -> policy.decideByRisk(context("WORKING_CAPITAL"), PARAMETERS,
                new RiskEstimate(4, "another-model", money("0")), DECIDED_AT)).isInstanceOf(InvalidRiskEstimateException.class);
    }

    @Test
    void rejectsOldSchemaAndNonFormalCutoff() {
        assertThatThrownBy(() -> new PolicyParameters("test", 3, "test-model", money("0.50"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyParameters("test", 4, "test-model", money("0.30"))).isInstanceOf(IllegalArgumentException.class);
    }
}
