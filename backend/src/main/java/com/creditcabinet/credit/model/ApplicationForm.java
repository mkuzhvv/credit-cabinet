package com.creditcabinet.credit.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.creditcabinet.common.json.MoneyJson;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApplicationForm(
        @JsonProperty(required = true) @MoneyJson @Schema(type = "string", nullable = true) BigDecimal amount,
        @JsonProperty(required = true) @Min(1) @Max(600) Integer termMonths,
        @JsonProperty(required = true) @MoneyJson @Schema(type = "string", nullable = true) BigDecimal averageMonthlyInflow,
        @JsonProperty(required = true) @MoneyJson @Schema(type = "string", nullable = true) BigDecimal averageMonthlyOutflow,
        @JsonProperty(required = true) @Size(min = 1, max = 1000) @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*") String financingPurpose,
        @JsonProperty(required = true) @MoneyJson @Schema(type = "string", nullable = true) BigDecimal projectBudget,
        @JsonProperty(required = true) @Min(1) @Max(600) Integer trancheTermMonths,
        @JsonProperty(required = true) @Valid GovernmentContractForm governmentContract,
        @JsonProperty(required = true) @MoneyJson @Schema(type = "string", nullable = true,
                description = "Свободные собственные деньги до нового кредита; неизвестное значение остаётся null") BigDecimal cashBalance) {

    public static ApplicationForm empty() {
        return new ApplicationForm(null, null, null, null, null, null, null, null, null);
    }

    public ApplicationForm withAverageMonthlyInflow(BigDecimal inflow) {
        return new ApplicationForm(amount, termMonths, inflow, averageMonthlyOutflow, financingPurpose,
                projectBudget, trancheTermMonths, governmentContract, cashBalance);
    }

    public record GovernmentContractForm(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = "44_FZ") String lawCode,
            @JsonProperty(required = true) @Size(min = 1, max = 100) @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*") String number,
            @JsonProperty(required = true) @MoneyJson @Schema(type = "string", nullable = true) BigDecimal contractAmount,
            @JsonProperty(required = true) @MoneyJson @Schema(type = "string", nullable = true) BigDecimal advanceAmount,
            @JsonProperty(required = true) LocalDate executionEndDate,
            @JsonProperty(required = true) LocalDate finalPaymentDate) {
    }
}
