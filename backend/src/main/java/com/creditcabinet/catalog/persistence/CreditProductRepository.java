package com.creditcabinet.catalog.persistence;

import java.util.List;
import java.util.Optional;

import com.creditcabinet.catalog.model.CreditProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface CreditProductRepository extends JpaRepository<CreditProduct, String> {

    List<CreditProduct> findTop100ByActiveTrueOrderByCodeAsc();

    Optional<CreditProduct> findByCodeAndActiveTrue(String code);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM CreditProduct p WHERE p.code = :code AND p.active = true")
    Optional<CreditProduct> findActiveForSubmission(String code);
}
