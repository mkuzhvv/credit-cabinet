package com.creditcabinet.credit.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.creditcabinet.credit.model.ApplicationForm.GovernmentContractForm;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "credit_application")
public class CreditApplication {

    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "created_by_user_id", nullable = false)
    private UUID createdByUserId;
    @Column(name = "product_code", nullable = false, length = 64)
    private String productCode;
    @Column(name = "current_assessment_id")
    private UUID currentAssessmentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(precision = 14, scale = 2)
    private BigDecimal amount;
    @Column(name = "term_months")
    private Integer termMonths;
    @Column(name = "average_monthly_inflow", precision = 14, scale = 2)
    private BigDecimal averageMonthlyInflow;
    @Column(name = "average_monthly_outflow", precision = 14, scale = 2)
    private BigDecimal averageMonthlyOutflow;
    @Column(name = "calculation_date", nullable = false)
    private LocalDate calculationDate;
    @Column(name = "financing_purpose", length = 1000)
    private String financingPurpose;
    @Column(name = "project_budget", precision = 14, scale = 2)
    private BigDecimal projectBudget;
    @Column(name = "tranche_term_months")
    private Integer trancheTermMonths;
    @Column(name = "gov_contract_present", nullable = false)
    private boolean governmentContractPresent;
    @Column(name = "gov_contract_number", length = 100)
    private String governmentContractNumber;
    @Column(name = "gov_contract_amount", precision = 14, scale = 2)
    private BigDecimal governmentContractAmount;
    @Column(name = "gov_advance_amount", precision = 14, scale = 2)
    private BigDecimal governmentAdvanceAmount;
    @Column(name = "gov_execution_end_date")
    private LocalDate governmentExecutionEndDate;
    @Column(name = "gov_final_payment_date")
    private LocalDate governmentFinalPaymentDate;
    @Column(name = "reporting_period_start", nullable = false)
    private LocalDate reportingPeriodStart;
    @Column(name = "reporting_period_end", nullable = false)
    private LocalDate reportingPeriodEnd;
    @Column(name = "last_submitted_at")
    private Instant lastSubmittedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreditApplication() {
    }

    public CreditApplication(UUID companyId, UUID createdByUserId, String productCode, int financialPeriodMonths, Instant now) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.createdByUserId = createdByUserId;
        this.productCode = productCode;
        this.status = ApplicationStatus.DRAFT;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
        this.calculationDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        YearMonth creationMonth = YearMonth.from(calculationDate);
        this.reportingPeriodStart = creationMonth.minusMonths(financialPeriodMonths).atDay(1);
        this.reportingPeriodEnd = creationMonth.minusMonths(1).atEndOfMonth();
    }

    public void replaceForm(ApplicationForm form, Instant now) {
        amount = form.amount();
        termMonths = form.termMonths();
        averageMonthlyInflow = form.averageMonthlyInflow();
        averageMonthlyOutflow = form.averageMonthlyOutflow();
        financingPurpose = form.financingPurpose();
        projectBudget = form.projectBudget();
        trancheTermMonths = form.trancheTermMonths();
        GovernmentContractForm contract = form.governmentContract();
        governmentContractPresent = contract != null;
        governmentContractNumber = contract == null ? null : contract.number();
        governmentContractAmount = contract == null ? null : contract.contractAmount();
        governmentAdvanceAmount = contract == null ? null : contract.advanceAmount();
        governmentExecutionEndDate = contract == null ? null : contract.executionEndDate();
        governmentFinalPaymentDate = contract == null ? null : contract.finalPaymentDate();
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public ApplicationForm getForm() {
        GovernmentContractForm contract = governmentContractPresent
                ? new GovernmentContractForm("44_FZ", governmentContractNumber, governmentContractAmount,
                        governmentAdvanceAmount, governmentExecutionEndDate, governmentFinalPaymentDate)
                : null;
        return new ApplicationForm(amount, termMonths, averageMonthlyInflow, averageMonthlyOutflow,
                financingPurpose, projectBudget, trancheTermMonths, contract);
    }

    public ReportingPeriod getReportingPeriod() {
        int months = (int) ChronoUnit.MONTHS.between(YearMonth.from(reportingPeriodStart), YearMonth.from(reportingPeriodEnd)) + 1;
        return new ReportingPeriod(reportingPeriodStart, reportingPeriodEnd, months);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getProductCode() {
        return productCode;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getCurrentAssessmentId() {
        return currentAssessmentId;
    }

    public Instant getLastSubmittedAt() {
        return lastSubmittedAt;
    }

    public LocalDate getCalculationDate() {
        return calculationDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
