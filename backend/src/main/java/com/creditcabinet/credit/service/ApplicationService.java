package com.creditcabinet.credit.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.creditcabinet.catalog.api.ProductView;
import com.creditcabinet.catalog.service.CatalogService;
import com.creditcabinet.common.error.ApiValidationException;
import com.creditcabinet.common.error.FieldIssue;
import com.creditcabinet.common.idempotency.IdempotencyResult;
import com.creditcabinet.common.idempotency.IdempotencyScope;
import com.creditcabinet.common.idempotency.IdempotencyService;
import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.credit.api.ApplicationPage;
import com.creditcabinet.credit.api.ApplicationSummary;
import com.creditcabinet.credit.api.ApplicationView;
import com.creditcabinet.credit.api.CreateApplicationRequest;
import com.creditcabinet.credit.api.SaveFormRequest;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.CreditApplication;
import com.creditcabinet.credit.persistence.ApplicationEventRepository;
import com.creditcabinet.credit.persistence.CreditApplicationRepository;
import com.creditcabinet.credit.persistence.CreditAssessmentRepository;
import com.creditcabinet.credit.model.CreditAssessment;
import com.creditcabinet.credit.api.ApplicationView.ClarificationRequest;
import com.creditcabinet.credit.api.ApplicationView.ProcessingError;
import com.creditcabinet.identity.model.CurrentUser;
import com.creditcabinet.identity.service.CurrentUserProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly = true)
public class ApplicationService {

    private final CreditApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final CatalogService catalog;
    private final CurrentUserProvider currentUsers;
    private final IdempotencyService idempotency;
    private final ApplicationFormValidator forms;
    private final Clock clock;
    private final CreditAssessmentRepository assessments;
    private final ClarificationService clarifications;

    public ApplicationService(CreditApplicationRepository applications, ApplicationEventRepository events,
            CatalogService catalog, CurrentUserProvider currentUsers, IdempotencyService idempotency,
            ApplicationFormValidator forms, Clock clock, CreditAssessmentRepository assessments, ClarificationService clarifications) {
        this.applications = applications;
        this.events = events;
        this.catalog = catalog;
        this.currentUsers = currentUsers;
        this.idempotency = idempotency;
        this.forms = forms;
        this.clock = clock;
        this.assessments = assessments;
        this.clarifications = clarifications;
    }

    @Transactional
    public StoredResponse create(CreateApplicationRequest request, UUID key, UUID requestId) {
        CurrentUser user = currentUsers.getCurrentUser();
        IdempotencyScope scope = new IdempotencyScope(user.userId(), user.companyId(), "applications.create.v1", "collection", key);
        IdempotencyResult result = idempotency.begin(scope, request, Duration.ofMinutes(1));
        if (result instanceof IdempotencyResult.Replay replay) {
            return replay.response();
        }
        IdempotencyResult.Claim claim = (IdempotencyResult.Claim) result;
        ProductView product = catalog.findActiveProduct(request.productCode())
                .orElseThrow(() -> new ApiValidationException(List.of(
                        new FieldIssue("productCode", "INVALID_VALUE", "Продукт не найден или недоступен"))));
        CreditApplication application = applications.saveAndFlush(new CreditApplication(
                user.companyId(), user.userId(), product.code(), product.terms().financialPeriodMonths(), clock.instant()));
        events.recordCreated(application.getId(), application.getVersion(), user.userId(), requestId, application.getCreatedAt());
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/api/v1/applications/" + application.getId()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return idempotency.complete(claim, 201, toView(application), headers);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationView getApplication(UUID id) {
        CreditApplication application = loadForCurrentCompany(id);
        return toView(application);
    }

    public ApplicationPage getApplications(int page, int size, ApplicationStatus status) {
        UUID companyId = currentUsers.getCurrentUser().companyId();
        long total = applications.countForCompany(companyId, status);
        long offset = (long) page * size;
        List<ApplicationSummary> items = offset >= total ? List.of()
                : applications.findSummaries(companyId, status, PageRequest.of(page, size));
        return new ApplicationPage(items, page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional
    public ApplicationView saveForm(UUID id, SaveFormRequest request) {
        CreditApplication application = applications.findForUpdate(id, currentUsers.getCurrentUser().companyId())
                .orElseThrow(ApplicationNotFoundException::new);
        if (!application.getVersion().equals(request.expectedVersion())) {
            throw ApplicationConflictException.versionConflict();
        }
        if (application.getStatus() != ApplicationStatus.DRAFT && application.getStatus() != ApplicationStatus.NEEDS_INFO) {
            throw ApplicationConflictException.invalidState();
        }
        forms.validate(application.getProductCode(), request.form());
        if (application.getStatus() == ApplicationStatus.NEEDS_INFO) {
            clarifications.validateFormUpdate(application, request.form());
        }
        application.replaceForm(request.form(), clock.instant());
        applications.flush();
        return toView(application);
    }

    private CreditApplication loadForCurrentCompany(UUID id) {
        return applications.findByIdAndCompanyId(id, currentUsers.getCurrentUser().companyId())
                .orElseThrow(ApplicationNotFoundException::new);
    }

    private ProductView productFor(CreditApplication application) {
        return catalog.findProduct(application.getProductCode())
                .orElseThrow(() -> new IllegalStateException("Продукт сохранённой заявки отсутствует"));
    }

    private ApplicationView toView(CreditApplication application) {
        CreditAssessment assessment = application.getCurrentAssessmentId() == null ? null
                : assessments.findByIdAndApplicationId(application.getCurrentAssessmentId(), application.getId()).orElseThrow();
        ProductView product;
        if (application.getStatus() == ApplicationStatus.DRAFT || application.getStatus() == ApplicationStatus.NEEDS_INFO) {
            product = productFor(application);
        } else {
            var snapshot = assessment.getInputSnapshot().product();
            product = new ProductView(snapshot.code(), snapshot.name(), snapshot.description(), snapshot.terms());
        }
        var result = assessment == null ? null : assessment.getResultPayload();
        var failure = result == null ? null : result.processingError();
        ProcessingError error = failure == null ? null : new ProcessingError(failure.code(), failure.message(), failure.occurredAt());
        List<ClarificationRequest> questions = application.getStatus() == ApplicationStatus.NEEDS_INFO
                ? clarifications.currentQuestions(application).stream().map(question -> new ClarificationRequest(question.getId(),
                        question.getFieldCode(), question.getRuleCode(), question.getMessage(), question.getObservedValue().toPlainString())).toList()
                : List.of();
        List<String> actions = switch (application.getStatus()) {
            case DRAFT, NEEDS_INFO -> List.of("UPDATE_FORM", "SUBMIT");
            case TECHNICAL_HOLD -> List.of("RETRY");
            default -> List.of();
        };
        return new ApplicationView(application.getId(), application.getVersion(), application.getStatus(), product,
                application.getReportingPeriod(), application.getForm(), application.getCurrentAssessmentId(),
                application.getLastSubmittedAt(), result == null ? null : result.decision(), questions, error, actions,
                application.getCreatedAt(), application.getUpdatedAt(), application.getCalculationDate());
    }
}
