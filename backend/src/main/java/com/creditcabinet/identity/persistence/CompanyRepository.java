package com.creditcabinet.identity.persistence;

import java.util.UUID;

import com.creditcabinet.identity.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
}
