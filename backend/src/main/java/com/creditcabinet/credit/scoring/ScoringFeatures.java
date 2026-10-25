package com.creditcabinet.credit.scoring;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.creditcabinet.common.json.MoneyJson;
import com.creditcabinet.credit.credithistory.CreditHistoryProfile;
import com.creditcabinet.credit.model.ReportingPeriod;

public record ScoringFeatures(String productCode, String currency, @MoneyJson BigDecimal amount, int termMonths,
        @MoneyJson BigDecimal averageMonthlyInflow, @MoneyJson BigDecimal averageMonthlyOutflow,
        ReportingPeriod reportingPeriod, LocalDate asOfDate, int businessAgeMonths, CreditHistoryProfile creditHistory,
        @MoneyJson BigDecimal projectBudget, Integer trancheTermMonths, GovernmentContractFeatures governmentContract,
        @MoneyJson BigDecimal cashBalance) {

    public record GovernmentContractFeatures(@MoneyJson BigDecimal contractAmount, @MoneyJson BigDecimal advanceAmount,
            LocalDate executionEndDate, LocalDate finalPaymentDate, LocalDate plannedEndDate) {
    }
}
