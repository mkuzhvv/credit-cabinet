package com.creditcabinet.assistant.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.creditcabinet.assistant.api.SendMessageRequest;
import com.creditcabinet.assistant.api.SendMessageRequest.MessageContext;
import com.creditcabinet.assistant.model.AssistantContext;
import com.creditcabinet.assistant.model.AssistantContext.ApplicationContext;
import com.creditcabinet.assistant.model.AssistantContext.Context;
import com.creditcabinet.assistant.model.AssistantContext.FieldValue;
import com.creditcabinet.assistant.model.AssistantContext.HistoryMessage;
import com.creditcabinet.catalog.service.CatalogService;
import com.creditcabinet.common.error.ApiValidationException;
import com.creditcabinet.common.error.FieldIssue;
import com.creditcabinet.credit.api.ApplicationView;
import com.creditcabinet.credit.persistence.CreditApplicationRepository;
import com.creditcabinet.credit.service.ApplicationNotFoundException;
import com.creditcabinet.credit.service.ApplicationService;
import com.creditcabinet.identity.model.CurrentUser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AssistantContextFactory {

    private final CatalogService catalog;
    private final ApplicationService applications;
    private final CreditApplicationRepository applicationRecords;
    private final AssistantFieldRegistry fields;
    private final AssistantCatalogMapper products;
    private final JsonMapper mapper;

    public AssistantContextFactory(CatalogService catalog, ApplicationService applications, CreditApplicationRepository applicationRecords,
            AssistantFieldRegistry fields, AssistantCatalogMapper products, JsonMapper mapper) {
        this.catalog = catalog;
        this.applications = applications;
        this.applicationRecords = applicationRecords;
        this.fields = fields;
        this.products = products;
        this.mapper = mapper;
    }

    public void checkAccess(MessageContext context, CurrentUser user) {
        if (context.applicationId() != null && !applicationRecords.existsByIdAndCompanyId(context.applicationId(), user.companyId())) {
            throw new ApplicationNotFoundException();
        }
    }

    public AssistantContext create(UUID conversationId, SendMessageRequest request, CurrentUser user, List<HistoryMessage> history) {
        var input = request.context();
        var available = catalog.getProducts();
        if (available.isEmpty()) {
            throw AssistantException.unavailable();
        }
        String selected = input.productCode();
        ApplicationView application = null;
        if (input.applicationId() != null) {
            applicationRecords.findForAssistant(input.applicationId(), user.companyId()).orElseThrow(ApplicationNotFoundException::new);
            application = applications.getApplication(input.applicationId());
            if (selected != null && !selected.equals(application.product().code())) {
                throw invalid("context.productCode", "Продукт не совпадает с выбранной заявкой");
            }
            selected = application.product().code();
        }
        String productCode = selected;
        if (selected != null && available.stream().noneMatch(product -> product.code().equals(productCode))) {
            throw invalid("context.productCode", "Продукт отсутствует в доступном каталоге");
        }
        if (input.fieldCode() != null && !fields.supports(input.fieldCode(), selected)) {
            throw invalid("context.fieldCode", "Поле неизвестно или не используется выбранным продуктом");
        }
        ApplicationView chosen = application;
        var contextCatalog = available.stream().map(product -> chosen != null && product.code().equals(productCode) ? chosen.product() : product)
                .map(products::toContext).toList();
        ApplicationContext applicationContext = application == null ? null : new ApplicationContext(application.id(), application.status(),
                application.product().code(), values(application, input.fieldCode()), application.calculationDate(), application.reportingPeriod());
        return new AssistantContext("2", conversationId, request.text(), List.copyOf(history),
                new Context(contextCatalog, fields.definitions(selected), selected, input.fieldCode(), applicationContext));
    }

    private List<FieldValue> values(ApplicationView application, String fieldCode) {
        Set<String> requested = new LinkedHashSet<>();
        if (fieldCode == null) {
            requested.add("form.amount");
            requested.add("form.termMonths");
        } else {
            requested.add(fieldCode);
        }
        String product = application.product().code();
        if ("form.amount".equals(fieldCode) && product.equals("OVERDRAFT")) {
            requested.add("form.averageMonthlyInflow");
        }
        if ("form.amount".equals(fieldCode) && product.equals("GOVERNMENT_CONTRACT_LINE")) {
            requested.add("form.governmentContract.contractAmount");
            requested.add("form.governmentContract.advanceAmount");
        }
        if ("form.governmentContract.advanceAmount".equals(fieldCode)) {
            requested.add("form.governmentContract.contractAmount");
        }
        if ("form.projectBudget".equals(fieldCode)) {
            requested.add("form.amount");
        }
        if ("form.cashBalance".equals(fieldCode)) {
            requested.add("form.averageMonthlyOutflow");
        }
        JsonNode form = mapper.valueToTree(application.form());
        return requested.stream().filter(code -> !code.equals("form.governmentContract.number"))
                .map(code -> new FieldValue(code, readValue(form, code))).toList();
    }

    private String readValue(JsonNode form, String code) {
        JsonNode value = form;
        for (String part : code.substring("form.".length()).split("\\.")) {
            value = value.path(part);
        }
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private ApiValidationException invalid(String field, String message) {
        return new ApiValidationException(List.of(new FieldIssue(field, "INVALID_VALUE", message)));
    }
}
