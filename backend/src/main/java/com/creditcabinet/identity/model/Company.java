package com.creditcabinet.identity.model;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company")
public class Company {

    @Id
    private UUID id;
    @Column(nullable = false, length = 300)
    private String name;
    @Column(name = "legal_form", nullable = false, length = 16)
    private String legalForm;
    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;
    @Column(name = "has_settlement_account", nullable = false)
    private boolean hasSettlementAccount;
    @Column(name = "has_regular_inflow_last_3_months", nullable = false)
    private boolean hasRegularInflowLast3Months;

    protected Company() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLegalForm() {
        return legalForm;
    }

    public LocalDate getRegistrationDate() {
        return registrationDate;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public boolean hasSettlementAccount() {
        return hasSettlementAccount;
    }

    public boolean hasRegularInflowLast3Months() {
        return hasRegularInflowLast3Months;
    }
}
