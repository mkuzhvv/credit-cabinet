package com.creditcabinet.catalog.persistence;

import java.util.List;
import java.util.Optional;

import com.creditcabinet.catalog.model.CreditProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditProductRepository extends JpaRepository<CreditProduct, String> {

    List<CreditProduct> findTop100ByActiveTrueOrderByCodeAsc();

    Optional<CreditProduct> findByCodeAndActiveTrue(String code);
}
