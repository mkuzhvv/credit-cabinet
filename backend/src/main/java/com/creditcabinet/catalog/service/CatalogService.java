package com.creditcabinet.catalog.service;

import java.util.List;
import java.util.Optional;

import com.creditcabinet.catalog.api.ProductView;
import com.creditcabinet.catalog.model.CreditProduct;
import com.creditcabinet.catalog.model.ProductTerms;
import com.creditcabinet.catalog.model.ProductTerms.GovernmentContractRules;
import com.creditcabinet.catalog.model.ProductTerms.OverdraftRules;
import com.creditcabinet.catalog.model.ProductTerms.RevolvingLineRules;
import com.creditcabinet.catalog.persistence.CreditProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final CreditProductRepository products;

    public CatalogService(CreditProductRepository products) {
        this.products = products;
    }

    public List<ProductView> getProducts() {
        return products.findTop100ByActiveTrueOrderByCodeAsc().stream()
                .map(this::toView)
                .toList();
    }

    public Optional<ProductView> findActiveProduct(String code) {
        return products.findByCodeAndActiveTrue(code).map(this::toView);
    }

    public Optional<ProductView> findProduct(String code) {
        return products.findById(code).map(this::toView);
    }

    public Optional<ProductTerms> findActiveProductTerms(String code) {
        return products.findByCodeAndActiveTrue(code).map(this::toTerms);
    }

    private ProductView toView(CreditProduct product) {
        return new ProductView(product.getCode(), product.getName(), product.getDescription(), toTerms(product));
    }

    private ProductTerms toTerms(CreditProduct product) {
        OverdraftRules overdraft = null;
        RevolvingLineRules revolving = null;
        GovernmentContractRules governmentContract = null;
        switch (product.getFinancingType()) {
            case "OVERDRAFT" -> overdraft = new OverdraftRules(
                    product.getMaxInflowSharePercent(), product.getMaxDebtPeriodDays());
            case "REVOLVING_LINE" -> revolving = new RevolvingLineRules(
                    product.getMinTrancheTermMonths(), product.getMaxTrancheTermMonths());
            case "NON_REVOLVING_LINE" -> governmentContract = new GovernmentContractRules(
                    product.getMaxNetContractSharePercent(), product.getMaxDaysAfterFinalPayment(), false);
            case "TERM_LOAN" -> { }
            default -> throw new IllegalStateException("Неизвестная механика продукта: " + product.getCode());
        }
        return new ProductTerms(
                product.getTermsVersion(), product.getCurrency(), product.getMinAmount(), product.getMaxAmount(),
                product.getMinTermMonths(), product.getMaxTermMonths(), product.getAnnualRatePercent(),
                product.getMinBusinessAgeMonths(), product.getFinancialPeriodMonths(),
                product.getFinancingType(), product.getRepaymentType(),
                product.requiresSettlementAccount(), product.requiresRegularInflow(),
                overdraft, revolving, governmentContract, product.getDisbursementDescription(),
                product.getRepaymentDescription(), product.getGeneralConditions());
    }
}
