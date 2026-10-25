package com.creditcabinet.credit.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.creditcabinet.credit.api.ApplicationSummary;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.CreditApplication;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CreditApplicationRepository extends JpaRepository<CreditApplication, UUID> {

    Optional<CreditApplication> findByIdAndCompanyId(UUID id, UUID companyId);

    @Query("""
            SELECT new com.creditcabinet.credit.api.ApplicationSummary(
                a.id, a.productCode, p.name, a.status, a.amount, a.termMonths, a.version, a.updatedAt)
            FROM CreditApplication a JOIN CreditProduct p ON p.code = a.productCode
            WHERE a.companyId = :companyId AND (:status IS NULL OR a.status = :status)
            ORDER BY a.updatedAt DESC, a.id DESC
            """)
    List<ApplicationSummary> findSummaries(UUID companyId, ApplicationStatus status, Pageable pageable);

    @Query("""
            SELECT count(a) FROM CreditApplication a
            WHERE a.companyId = :companyId AND (:status IS NULL OR a.status = :status)
            """)
    long countForCompany(UUID companyId, ApplicationStatus status);
}
