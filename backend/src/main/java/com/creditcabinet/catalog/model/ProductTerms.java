package com.creditcabinet.catalog.model;

import java.math.BigDecimal;

import com.creditcabinet.common.json.MoneyJson;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

public record ProductTerms(
        String version,
        String currency,
        @MoneyJson @Schema(type = "string", example = "100000.00") BigDecimal minAmount,
        @MoneyJson @Schema(type = "string", example = "3000000.00") BigDecimal maxAmount,
        int minTermMonths,
        int maxTermMonths,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "24.00") BigDecimal annualRatePercent,
        int minBusinessAgeMonths,
        int financialPeriodMonths,
        String financingType,
        String repaymentType,
        boolean requiresSettlementAccount,
        boolean requiresRegularInflow,
        OverdraftRules overdraftRules,
        RevolvingLineRules revolvingLineRules,
        GovernmentContractRules governmentContractRules,
        String disbursementDescription,
        String repaymentDescription,
        String generalConditions) {

    public record OverdraftRules(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxInflowSharePercent,
            int maxDebtPeriodDays) {
    }

    public record RevolvingLineRules(int minTrancheTermMonths, int maxTrancheTermMonths) {
    }

    public record GovernmentContractRules(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxNetContractSharePercent,
            int maxDaysAfterFinalPayment, boolean renewable) {
    }
}
