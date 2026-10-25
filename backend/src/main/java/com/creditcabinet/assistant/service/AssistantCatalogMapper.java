package com.creditcabinet.assistant.service;

import java.util.ArrayList;
import java.util.List;

import com.creditcabinet.assistant.model.AssistantContext.CatalogProduct;
import com.creditcabinet.catalog.api.ProductView;
import org.springframework.stereotype.Component;

@Component
public class AssistantCatalogMapper {

    public CatalogProduct toContext(ProductView product) {
        var terms = product.terms();
        List<String> conditions = new ArrayList<>();
        conditions.add("Сумма/лимит " + terms.minAmount().toPlainString() + "–" + terms.maxAmount().toPlainString()
                + " " + terms.currency() + "; ставка " + terms.annualRatePercent().toPlainString() + "% годовых на остаток долга.");
        conditions.add("Срок кредита или линии " + terms.minTermMonths() + "–" + terms.maxTermMonths()
                + " месяцев; бизнес от " + terms.minBusinessAgeMonths() + " месяцев.");
        conditions.add("Финансовые показатели за " + terms.financialPeriodMonths() + " полных календарных месяцев.");
        append(conditions, terms.disbursementDescription());
        append(conditions, terms.repaymentDescription());
        append(conditions, terms.generalConditions());
        if (terms.overdraftRules() != null) {
            conditions.add("Лимит не больше " + terms.overdraftRules().maxInflowSharePercent().toPlainString()
                    + "% среднемесячных поступлений; задолженность полностью погашается за " + terms.overdraftRules().maxDebtPeriodDays() + " дней.");
        }
        if (terms.revolvingLineRules() != null) {
            conditions.add("Срок транша " + terms.revolvingLineRules().minTrancheTermMonths() + "–"
                    + terms.revolvingLineRules().maxTrancheTermMonths() + " месяцев; погашенный основной долг восстанавливает лимит.");
        }
        if (terms.governmentContractRules() != null) {
            conditions.add("Лимит не больше " + terms.governmentContractRules().maxNetContractSharePercent().toPlainString()
                    + "% × (стоимость контракта − аванс). Конец финансирования не позже окончательной оплаты плюс "
                    + terms.governmentContractRules().maxDaysAfterFinalPayment() + " дней. Лимит не восстанавливается.");
        }
        if (product.code().equals("LONG_TERM")) {
            conditions.add("Нужны цель финансирования и положительный бюджет проекта; отдельного ограничения сумма<=бюджет в MVP нет.");
        }
        return new CatalogProduct(product.code(), product.name(), product.description(), terms.version(), List.copyOf(conditions),
                terms.financingType(), terms.financialPeriodMonths(), terms);
    }

    private void append(List<String> conditions, String text) {
        for (int offset = 0; offset < text.length(); offset += 1000) {
            conditions.add(text.substring(offset, Math.min(offset + 1000, text.length())));
        }
    }
}
