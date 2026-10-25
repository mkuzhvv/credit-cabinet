package com.creditcabinet.credit.credithistory;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.creditcabinet.common.json.MoneyJson;
import com.creditcabinet.credit.model.CreditHistoryStatus;
import com.creditcabinet.credit.model.ReportingPeriod;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CreditHistoryProfile(
        @JsonProperty(required = true) CreditHistoryStatus status,
        @JsonProperty(required = true) LocalDate asOfDate,
        @JsonProperty(required = true) ReportingPeriod delinquencyPeriod,
        @JsonProperty(required = true) @MoneyJson BigDecimal totalOutstandingDebt,
        @JsonProperty(required = true) @MoneyJson BigDecimal monthlyDebtPayment,
        @JsonProperty(required = true) @MoneyJson BigDecimal overdueDebtAmount,
        @JsonProperty(required = true) Integer maxDaysPastDueLast12Months) {
}
