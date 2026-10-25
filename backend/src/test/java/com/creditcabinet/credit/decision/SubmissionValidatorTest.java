package com.creditcabinet.credit.decision;

import java.time.LocalDate;
import java.util.List;

import com.creditcabinet.common.error.FieldIssue;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.model.ApplicationForm.GovernmentContractForm;
import com.creditcabinet.credit.model.ReportingPeriod;
import com.creditcabinet.credit.service.ApplicationFormValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.creditcabinet.credit.decision.DecisionTestData.*;
import static org.assertj.core.api.Assertions.assertThat;

class SubmissionValidatorTest {

    private final SubmissionValidator validator = new SubmissionValidator(new ApplicationFormValidator());

    @ParameterizedTest
    @ValueSource(strings = {"WORKING_CAPITAL", "OVERDRAFT", "REVOLVING_LINE", "LONG_TERM", "GOVERNMENT_CONTRACT_LINE"})
    void acceptsProductBoundariesAndRejectsValuesOutsideThem(String code) {
        SubmissionContext input = context(code);
        ApplicationForm form = input.form();
        var terms = input.terms();
        assertThat(validate(input, financing(form, terms.minAmount(), terms.minTermMonths()))).isEmpty();
        assertThat(validate(input, financing(form, terms.maxAmount(), terms.maxTermMonths()))).isEmpty();
        assertField(validate(input, financing(form, terms.minAmount().subtract(money("0.01")), form.termMonths())), "form.amount");
        assertField(validate(input, financing(form, terms.maxAmount().add(money("0.01")), form.termMonths())), "form.amount");
        assertField(validate(input, financing(form, form.amount(), terms.minTermMonths() - 1)), "form.termMonths");
        assertField(validate(input, financing(form, form.amount(), terms.maxTermMonths() + 1)), "form.termMonths");
    }

    @Test
    void requiresCommonFieldsAndDoesNotRoundInputMoney() {
        SubmissionContext input = context("WORKING_CAPITAL");
        assertThat(validate(input, ApplicationForm.empty())).extracting(FieldIssue::field)
                .contains("form.amount", "form.termMonths", "form.averageMonthlyInflow", "form.averageMonthlyOutflow");
        assertField(validate(input, financing(input.form(), money("100000.001"), 3)), "form.amount");
        assertField(validate(input, inflow(input.form(), "-0.01")), "form.averageMonthlyInflow");
        assertField(validate(input, inflow(input.form(), "1000000000000.00")), "form.averageMonthlyInflow");
    }

    @ParameterizedTest
    @CsvSource({"WORKING_CAPITAL,6", "OVERDRAFT,3", "REVOLVING_LINE,6", "LONG_TERM,12", "GOVERNMENT_CONTRACT_LINE,12"})
    void keepsFinancialPeriodTiedToDraftDateWhenSubmissionDateChanges(String code, int months) {
        SubmissionContext input = context(code);
        assertThat(input.reportingPeriod().months()).isEqualTo(months);
        SubmissionContext later = new SubmissionContext(code, input.terms(), input.form(), input.calculationDate(),
                input.reportingPeriod(), DATE.plusDays(1));
        if (code.equals("GOVERNMENT_CONTRACT_LINE")) {
            later = withForm(later, government(later.form(), contract("20000000.00", "0.00", DATE.plusDays(1), DATE.plusYears(1))));
        }
        assertThat(validator.validate(later)).isEmpty();
        ReportingPeriod wrong = new ReportingPeriod(input.reportingPeriod().startDate().plusDays(1), input.reportingPeriod().endDate(), months);
        assertField(validator.validate(new SubmissionContext(code, input.terms(), input.form(), DATE, wrong, DATE)), "reportingPeriod");
    }

    @Test
    void appliesOverdraftInflowLimitWithFloorToKopeck() {
        SubmissionContext input = context("OVERDRAFT");
        ApplicationForm form = inflow(input.form(), "200000.03");
        assertThat(validate(input, financing(form, money("60000.00"), 12))).isEmpty();
        assertField(validate(input, financing(form, money("60000.01"), 12)), "form.amount");
        assertField(validate(input, inflow(input.form(), "0.00")), "form.amount");
    }

    @Test
    void requiresPurposeAndPositiveBudgetWithoutCappingCreditByBudget() {
        SubmissionContext input = context("LONG_TERM");
        ApplicationForm form = input.form();
        ApplicationForm missing = new ApplicationForm(form.amount(), form.termMonths(), form.averageMonthlyInflow(),
                form.averageMonthlyOutflow(), "  ", money("0.00"), null, null);
        assertThat(validate(input, missing)).extracting(FieldIssue::field).contains("form.financingPurpose", "form.projectBudget");
        ApplicationForm lowBudget = new ApplicationForm(money("5000000.00"), 60, form.averageMonthlyInflow(),
                form.averageMonthlyOutflow(), "Расширение", money("1.00"), null, null);
        assertThat(validate(input, lowBudget)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"2,false", "3,true", "6,true", "7,false"})
    void validatesTrancheTerm(int term, boolean valid) {
        SubmissionContext input = context("REVOLVING_LINE");
        ApplicationForm form = input.form();
        List<FieldIssue> issues = validate(input, new ApplicationForm(form.amount(), 24, form.averageMonthlyInflow(),
                form.averageMonthlyOutflow(), null, null, term, null));
        assertThat(issues.isEmpty()).isEqualTo(valid);
        if (!valid) {
            assertField(issues, "form.trancheTermMonths");
        }
    }

    @Test
    void rejectsSpecialFieldsOnOtherProducts() {
        SubmissionContext input = context("WORKING_CAPITAL");
        ApplicationForm form = input.form();
        assertThat(validate(input, new ApplicationForm(form.amount(), 3, form.averageMonthlyInflow(), form.averageMonthlyOutflow(),
                "Цель", money("1.00"), 3, contract("20000000.00", "0.00", DATE, DATE.plusYears(1)))))
                .extracting(FieldIssue::field).contains("form.financingPurpose", "form.projectBudget", "form.trancheTermMonths", "form.governmentContract");
    }

    @Test
    void usesSeventyPercentOfCostMinusAdvanceAndFloorsLimit() {
        SubmissionContext input = context("GOVERNMENT_CONTRACT_LINE");
        ApplicationForm form = government(input.form(), contract("10000000.00", "2000000.00", DATE, DATE.plusYears(1)));
        assertThat(validate(input, financing(form, money("5600000.00"), 12))).isEmpty();
        assertField(validate(input, financing(form, money("5600000.01"), 12)), "form.amount");
        form = government(input.form(), contract("1000000.01", "0.00", DATE, DATE.plusYears(1)));
        assertThat(validate(input, financing(form, money("700000.00"), 12))).isEmpty();
        assertField(validate(input, financing(form, money("700000.01"), 12)), "form.amount");
        assertField(validate(input, government(input.form(), contract("100.00", "101.00", DATE, DATE.plusYears(1)))), "form.governmentContract.advanceAmount");
        assertField(validate(input, government(input.form(), contract("100.00", "100.00", DATE, DATE.plusYears(1)))), "form.amount");
    }

    @Test
    void requiresCompleteGovernmentContract() {
        SubmissionContext input = context("GOVERNMENT_CONTRACT_LINE");
        assertField(validate(input, government(input.form(), null)), "form.governmentContract");
        assertThat(validate(input, government(input.form(), new GovernmentContractForm("OTHER", null, null, null, null, null))))
                .extracting(FieldIssue::field).contains("form.governmentContract.lawCode", "form.governmentContract.number",
                        "form.governmentContract.contractAmount", "form.governmentContract.advanceAmount",
                        "form.governmentContract.executionEndDate", "form.governmentContract.finalPaymentDate");
    }

    @Test
    void checksInclusiveExecutionAndFinalPaymentDeadlines() {
        SubmissionContext input = context("GOVERNMENT_CONTRACT_LINE");
        LocalDate end = DATE.plusMonths(1);
        ApplicationForm base = financing(input.form(), input.form().amount(), 1);
        assertThat(validate(input, government(base, contract("20000000.00", "0.00", end, end.minusDays(30))))).isEmpty();
        assertField(validate(input, government(base, contract("20000000.00", "0.00", DATE.minusDays(1), end))), "form.governmentContract.executionEndDate");
        assertField(validate(input, government(base, contract("20000000.00", "0.00", end.plusDays(1), end))), "form.termMonths");
        assertField(validate(input, government(base, contract("20000000.00", "0.00", end, end.minusDays(31)))), "form.termMonths");
    }

    private List<FieldIssue> validate(SubmissionContext input, ApplicationForm form) {
        return validator.validate(withForm(input, form));
    }

    private void assertField(List<FieldIssue> issues, String field) {
        assertThat(issues).extracting(FieldIssue::field).contains(field);
    }
}
