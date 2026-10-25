package com.creditcabinet.credit.decision;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.creditcabinet.catalog.model.ProductTerms;
import com.creditcabinet.catalog.model.ProductTerms.GovernmentContractRules;
import com.creditcabinet.catalog.model.ProductTerms.OverdraftRules;
import com.creditcabinet.catalog.model.ProductTerms.RevolvingLineRules;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.model.ApplicationForm.GovernmentContractForm;
import com.creditcabinet.credit.model.ReportingPeriod;

public final class DecisionTestData {

    static final LocalDate DATE = LocalDate.of(2026, 10, 25);

    static ProductTerms terms(String code) {
        return switch (code) {
            case "WORKING_CAPITAL" -> terms(code, "100000.00", "3000000.00", 3, 12, "24.00", 6, 6, "TERM_LOAN", "ANNUITY", null, null, null);
            case "OVERDRAFT" -> terms(code, "50000.00", "1000000.00", 12, 12, "26.00", 6, 3, "OVERDRAFT", "ACCOUNT_SWEEP",
                    new OverdraftRules(money("30.00"), 30), null, null);
            case "REVOLVING_LINE" -> terms(code, "300000.00", "5000000.00", 24, 24, "23.00", 12, 6,
                    "REVOLVING_LINE", "EQUAL_PRINCIPAL_PER_TRANCHE", null, new RevolvingLineRules(3, 6), null);
            case "LONG_TERM" -> terms(code, "500000.00", "5000000.00", 24, 60, "22.00", 12, 12, "TERM_LOAN", "ANNUITY", null, null, null);
            case "GOVERNMENT_CONTRACT_LINE" -> terms(code, "300000.00", "10000000.00", 1, 12, "21.00", 12, 12,
                    "NON_REVOLVING_LINE", "CONTRACT_PROCEEDS", null, null, new GovernmentContractRules(money("70.00"), 30, false));
            default -> throw new IllegalArgumentException(code);
        };
    }

    public static SubmissionContext context(String code) {
        ProductTerms terms = terms(code);
        ApplicationForm form = new ApplicationForm(terms.minAmount(), terms.minTermMonths(), money("10000000.00"), money("800000.00"),
                code.equals("LONG_TERM") ? "Расширение пекарни" : null, code.equals("LONG_TERM") ? money("600000.00") : null,
                code.equals("REVOLVING_LINE") ? 3 : null,
                code.equals("GOVERNMENT_CONTRACT_LINE") ? contract("20000000.00", "0.00", DATE, DATE.plusYears(1)) : null, money("1000000.00"));
        LocalDate start = switch (terms.financialPeriodMonths()) {
            case 3 -> LocalDate.of(2026, 7, 1);
            case 6 -> LocalDate.of(2026, 4, 1);
            case 12 -> LocalDate.of(2025, 10, 1);
            default -> throw new IllegalArgumentException();
        };
        return new SubmissionContext(code, terms, form, DATE,
                new ReportingPeriod(start, LocalDate.of(2026, 9, 30), terms.financialPeriodMonths()), DATE);
    }

    static SubmissionContext withForm(SubmissionContext input, ApplicationForm form) {
        return new SubmissionContext(input.productCode(), input.terms(), form, input.calculationDate(), input.reportingPeriod(), input.asOfDate());
    }

    static ApplicationForm financing(ApplicationForm form, BigDecimal amount, Integer term) {
        return new ApplicationForm(amount, term, form.averageMonthlyInflow(), form.averageMonthlyOutflow(),
                form.financingPurpose(), form.projectBudget(), form.trancheTermMonths(), form.governmentContract(), form.cashBalance());
    }

    static ApplicationForm inflow(ApplicationForm form, String value) {
        return new ApplicationForm(form.amount(), form.termMonths(), money(value), form.averageMonthlyOutflow(),
                form.financingPurpose(), form.projectBudget(), form.trancheTermMonths(), form.governmentContract(), form.cashBalance());
    }

    static ApplicationForm government(ApplicationForm form, GovernmentContractForm contract) {
        return new ApplicationForm(form.amount(), form.termMonths(), form.averageMonthlyInflow(), form.averageMonthlyOutflow(), null, null, null, contract, form.cashBalance());
    }

    static GovernmentContractForm contract(String cost, String advance, LocalDate execution, LocalDate payment) {
        return new GovernmentContractForm("44_FZ", "DEMO-44-001", money(cost), money(advance), execution, payment);
    }

    static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static ProductTerms terms(String code, String min, String max, int minTerm, int maxTerm, String rate,
            int age, int period, String financing, String repayment, OverdraftRules overdraft,
            RevolvingLineRules revolving, GovernmentContractRules government) {
        return new ProductTerms("test-terms-" + code, "RUB", money(min), money(max), minTerm, maxTerm, money(rate), age, period,
                financing, repayment, overdraft != null, overdraft != null, overdraft, revolving, government,
                "Демонстрационная выдача", "Погашение по условиям продукта", "Условия для проверки правил");
    }
}
