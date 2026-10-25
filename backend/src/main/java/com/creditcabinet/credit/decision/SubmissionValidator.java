package com.creditcabinet.credit.decision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.creditcabinet.catalog.model.ProductTerms;
import com.creditcabinet.common.error.FieldIssue;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.model.ApplicationForm.GovernmentContractForm;
import com.creditcabinet.credit.model.ReportingPeriod;
import com.creditcabinet.credit.service.ApplicationFormValidator;
import org.springframework.stereotype.Component;

@Component
public class SubmissionValidator {

    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
    private final ApplicationFormValidator forms;

    public SubmissionValidator(ApplicationFormValidator forms) {
        this.forms = forms;
    }

    public List<FieldIssue> validate(SubmissionContext input) {
        ApplicationForm form = input.form();
        ProductTerms terms = input.terms();
        List<FieldIssue> issues = new ArrayList<>(forms.findIssues(input.productCode(), form));
        boolean validAmount = money(issues, "form.amount", form.amount(), true);
        if (validAmount && (form.amount().compareTo(terms.minAmount()) < 0 || form.amount().compareTo(terms.maxAmount()) > 0)) {
            issue(issues, "form.amount", "OUT_OF_RANGE", "Сумма должна быть от " + terms.minAmount() + " до " + terms.maxAmount());
            validAmount = false;
        }
        boolean validTerm = term(issues, "form.termMonths", form.termMonths(), terms.minTermMonths(), terms.maxTermMonths());
        boolean validInflow = money(issues, "form.averageMonthlyInflow", form.averageMonthlyInflow(), false);
        money(issues, "form.averageMonthlyOutflow", form.averageMonthlyOutflow(), false);
        validatePeriod(input, issues);

        switch (input.productCode()) {
            case "WORKING_CAPITAL" -> { }
            case "OVERDRAFT" -> {
                if (validAmount && validInflow) {
                    BigDecimal limit = percentage(form.averageMonthlyInflow(), terms.overdraftRules().maxInflowSharePercent())
                            .min(terms.maxAmount());
                    amountLimit(issues, form.amount(), limit);
                }
            }
            case "REVOLVING_LINE" -> term(issues, "form.trancheTermMonths", form.trancheTermMonths(),
                    terms.revolvingLineRules().minTrancheTermMonths(), terms.revolvingLineRules().maxTrancheTermMonths());
            case "LONG_TERM" -> {
                text(issues, "form.financingPurpose", form.financingPurpose(), 1000);
                money(issues, "form.projectBudget", form.projectBudget(), true);
            }
            case "GOVERNMENT_CONTRACT_LINE" -> validateContract(input, issues, validAmount, validTerm);
            default -> throw new IllegalArgumentException("Неподдерживаемый продукт: " + input.productCode());
        }
        return List.copyOf(issues);
    }

    private void validatePeriod(SubmissionContext input, List<FieldIssue> issues) {
        int months = input.terms().financialPeriodMonths();
        YearMonth month = YearMonth.from(input.calculationDate());
        ReportingPeriod expected = new ReportingPeriod(month.minusMonths(months).atDay(1), month.minusMonths(1).atEndOfMonth(), months);
        if (!expected.equals(input.reportingPeriod())) {
            issue(issues, "reportingPeriod", "INVALID_VALUE", "Финансовый период не соответствует продукту и дате создания черновика");
        }
    }

    private void validateContract(SubmissionContext input, List<FieldIssue> issues, boolean validAmount, boolean validTerm) {
        GovernmentContractForm contract = input.form().governmentContract();
        if (contract == null) {
            issue(issues, "form.governmentContract", "REQUIRED", "Заполните сведения о госконтракте");
            return;
        }
        if (!"44_FZ".equals(contract.lawCode())) {
            issue(issues, "form.governmentContract.lawCode", "INVALID_VALUE", "Поддерживается контракт по 44-ФЗ");
        }
        text(issues, "form.governmentContract.number", contract.number(), 100);
        boolean validCost = money(issues, "form.governmentContract.contractAmount", contract.contractAmount(), true);
        boolean validAdvance = money(issues, "form.governmentContract.advanceAmount", contract.advanceAmount(), false);
        if (validCost && validAdvance) {
            if (contract.advanceAmount().compareTo(contract.contractAmount()) > 0) {
                issue(issues, "form.governmentContract.advanceAmount", "OUT_OF_RANGE", "Аванс не может превышать стоимость контракта");
            } else if (validAmount) {
                BigDecimal net = contract.contractAmount().subtract(contract.advanceAmount());
                BigDecimal limit = percentage(net, input.terms().governmentContractRules().maxNetContractSharePercent())
                        .min(input.terms().maxAmount());
                amountLimit(issues, input.form().amount(), limit);
            }
        }
        LocalDate execution = contract.executionEndDate();
        LocalDate payment = contract.finalPaymentDate();
        if (execution == null) {
            issue(issues, "form.governmentContract.executionEndDate", "REQUIRED", "Укажите дату завершения исполнения");
        } else if (execution.isBefore(input.asOfDate())) {
            issue(issues, "form.governmentContract.executionEndDate", "OUT_OF_RANGE", "Исполнение не должно завершаться раньше даты рассмотрения");
        }
        if (payment == null) {
            issue(issues, "form.governmentContract.finalPaymentDate", "REQUIRED", "Укажите дату окончательной оплаты");
        }
        if (validTerm) {
            LocalDate plannedEnd = input.asOfDate().plusMonths(input.form().termMonths());
            if (execution != null && plannedEnd.isBefore(execution)) {
                issue(issues, "form.termMonths", "OUT_OF_RANGE", "Срок финансирования не покрывает исполнение контракта");
            }
            if (payment != null && plannedEnd.isAfter(payment.plusDays(input.terms().governmentContractRules().maxDaysAfterFinalPayment()))) {
                issue(issues, "form.termMonths", "OUT_OF_RANGE", "Окончание финансирования позже допустимого срока после оплаты контракта");
            }
        }
    }

    private BigDecimal percentage(BigDecimal value, BigDecimal percent) {
        return value.multiply(percent).movePointLeft(2).setScale(2, RoundingMode.FLOOR);
    }

    private void amountLimit(List<FieldIssue> issues, BigDecimal amount, BigDecimal limit) {
        if (amount.compareTo(limit) > 0) {
            issue(issues, "form.amount", "OUT_OF_RANGE", "Сумма превышает доступный предел " + limit.toPlainString() + " RUB");
        }
    }

    private boolean money(List<FieldIssue> issues, String field, BigDecimal value, boolean positive) {
        if (value == null) {
            issue(issues, field, "REQUIRED", "Заполните поле");
            return false;
        }
        if (value.signum() < 0 || value.compareTo(MAX_MONEY) > 0 || value.stripTrailingZeros().scale() > 2) {
            issue(issues, field, "INVALID_VALUE", "Укажите неотрицательную сумму в допустимом диапазоне с точностью до копейки");
            return false;
        }
        if (positive && value.signum() == 0) {
            issue(issues, field, "POSITIVE_REQUIRED", "Сумма должна быть положительной");
            return false;
        }
        return true;
    }

    private boolean term(List<FieldIssue> issues, String field, Integer value, int min, int max) {
        if (value == null) {
            issue(issues, field, "REQUIRED", "Заполните поле");
            return false;
        }
        if (value < min || value > max) {
            issue(issues, field, "OUT_OF_RANGE", "Срок должен быть от " + min + " до " + max + " месяцев");
            return false;
        }
        return true;
    }

    private void text(List<FieldIssue> issues, String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            issue(issues, field, "REQUIRED", "Заполните поле");
        } else if (value.length() > maxLength) {
            issue(issues, field, "INVALID_LENGTH", "Превышена допустимая длина поля");
        }
    }

    private void issue(List<FieldIssue> issues, String field, String code, String message) {
        issues.add(new FieldIssue(field, code, message));
    }
}
