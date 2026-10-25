package com.creditcabinet.identity.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    protected AppUser() {
    }

    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getDisplayName() {
        return displayName;
    }
}
