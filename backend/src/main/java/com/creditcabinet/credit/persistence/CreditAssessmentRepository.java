package com.creditcabinet.credit.persistence;

import java.util.Optional;
import java.util.UUID;

import com.creditcabinet.credit.model.CreditAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditAssessmentRepository extends JpaRepository<CreditAssessment, UUID> {

    Optional<CreditAssessment> findByIdAndApplicationId(UUID id, UUID applicationId);
}
