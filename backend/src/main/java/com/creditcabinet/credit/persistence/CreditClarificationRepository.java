package com.creditcabinet.credit.persistence;

import java.util.List;
import java.util.UUID;

import com.creditcabinet.credit.model.CreditClarification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CreditClarificationRepository extends JpaRepository<CreditClarification, UUID> {

    @Query("""
            SELECT c FROM CreditClarification c
            WHERE c.applicationId = :applicationId AND c.sourceAssessmentId = :assessmentId
                AND c.responseAssessmentId IS NULL
            ORDER BY c.createdAt, c.id
            """)
    List<CreditClarification> findOpen(UUID applicationId, UUID assessmentId);
}
