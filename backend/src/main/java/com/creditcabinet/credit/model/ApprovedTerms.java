package com.creditcabinet.credit.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.creditcabinet.common.json.MoneyJson;
import com.fasterxml.jackson.annotation.JsonFormat;

public record ApprovedTerms(@MoneyJson BigDecimal amount, String currency, int termMonths,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal annualRatePercent, String repaymentDescription,
        String financingType, String repaymentType, LocalDate plannedEndDate, Integer trancheTermMonths, Integer maxDebtPeriodDays) {
}
