package com.creditcabinet.catalog.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "credit_product")
public class CreditProduct {

    @Id
    @Column(length = 64)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 3000)
    private String description;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "terms_version", nullable = false, length = 64)
    private String termsVersion;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "min_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal minAmount;
    @Column(name = "max_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal maxAmount;
    @Column(name = "min_term_months", nullable = false)
    private int minTermMonths;
    @Column(name = "max_term_months", nullable = false)
    private int maxTermMonths;
    @Column(name = "annual_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualRatePercent;
    @Column(name = "min_business_age_months", nullable = false)
    private int minBusinessAgeMonths;
    @Column(name = "financial_period_months", nullable = false)
    private int financialPeriodMonths;
    @Column(name = "financing_type", nullable = false, length = 40)
    private String financingType;
    @Column(name = "repayment_type", nullable = false, length = 40)
    private String repaymentType;
    @Column(name = "requires_settlement_account", nullable = false)
    private boolean requiresSettlementAccount;
    @Column(name = "requires_regular_inflow", nullable = false)
    private boolean requiresRegularInflow;
    @Column(name = "max_inflow_share_percent", precision = 5, scale = 2)
    private BigDecimal maxInflowSharePercent;
    @Column(name = "max_debt_period_days")
    private Integer maxDebtPeriodDays;
    @Column(name = "min_tranche_term_months")
    private Integer minTrancheTermMonths;
    @Column(name = "max_tranche_term_months")
    private Integer maxTrancheTermMonths;
    @Column(name = "max_net_contract_share_percent", precision = 5, scale = 2)
    private BigDecimal maxNetContractSharePercent;
    @Column(name = "max_days_after_final_payment")
    private Integer maxDaysAfterFinalPayment;
    @Column(name = "general_conditions", nullable = false, length = 3000)
    private String generalConditions;
    @Column(name = "disbursement_description", nullable = false, length = 1000)
    private String disbursementDescription;
    @Column(name = "repayment_description", nullable = false, length = 1000)
    private String repaymentDescription;

    protected CreditProduct() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public int getMinTermMonths() {
        return minTermMonths;
    }

    public int getMaxTermMonths() {
        return maxTermMonths;
    }

    public BigDecimal getAnnualRatePercent() {
        return annualRatePercent;
    }

    public int getMinBusinessAgeMonths() {
        return minBusinessAgeMonths;
    }

    public int getFinancialPeriodMonths() {
        return financialPeriodMonths;
    }

    public String getFinancingType() {
        return financingType;
    }

    public String getRepaymentType() {
        return repaymentType;
    }

    public boolean requiresSettlementAccount() {
        return requiresSettlementAccount;
    }

    public boolean requiresRegularInflow() {
        return requiresRegularInflow;
    }

    public BigDecimal getMaxInflowSharePercent() {
        return maxInflowSharePercent;
    }

    public Integer getMaxDebtPeriodDays() {
        return maxDebtPeriodDays;
    }

    public Integer getMinTrancheTermMonths() {
        return minTrancheTermMonths;
    }

    public Integer getMaxTrancheTermMonths() {
        return maxTrancheTermMonths;
    }

    public BigDecimal getMaxNetContractSharePercent() {
        return maxNetContractSharePercent;
    }

    public Integer getMaxDaysAfterFinalPayment() {
        return maxDaysAfterFinalPayment;
    }

    public String getGeneralConditions() {
        return generalConditions;
    }

    public String getDisbursementDescription() {
        return disbursementDescription;
    }

    public String getRepaymentDescription() {
        return repaymentDescription;
    }
}
