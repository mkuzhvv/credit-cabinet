package com.creditcabinet.credit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.creditcabinet.catalog.service.CatalogService;
import com.creditcabinet.common.error.ApiValidationException;
import com.creditcabinet.common.error.FieldIssue;
import com.creditcabinet.common.idempotency.IdempotencyResult;
import com.creditcabinet.common.idempotency.IdempotencyScope;
import com.creditcabinet.common.idempotency.IdempotencyService;
import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.credit.api.SubmitApplicationRequest;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.InflowConfirmation;
import com.creditcabinet.credit.decision.PolicyParameters;
import com.creditcabinet.credit.decision.SubmissionValidator;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.AssessmentInput;
import com.creditcabinet.credit.model.AssessmentInput.CompanySnapshot;
import com.creditcabinet.credit.model.AssessmentInput.ProductSnapshot;
import com.creditcabinet.credit.model.CreditAssessment;
import com.creditcabinet.credit.persistence.CreditApplicationRepository;
import com.creditcabinet.credit.persistence.CreditAssessmentRepository;
import com.creditcabinet.identity.persistence.CompanyRepository;
import com.creditcabinet.identity.service.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmitApplicationService {

    private final CreditApplicationRepository applications;
    private final CreditAssessmentRepository assessments;
    private final CompanyRepository companies;
    private final CatalogService catalog;
    private final CurrentUserProvider currentUsers;
    private final IdempotencyService idempotency;
    private final SubmissionValidator forms;
    private final ClarificationService clarifications;
    private final AssessmentAcceptanceService acceptance;
    private final PolicyParameters policy;
    private final Clock clock;

    public SubmitApplicationService(CreditApplicationRepository applications, CreditAssessmentRepository assessments,
            CompanyRepository companies, CatalogService catalog, CurrentUserProvider currentUsers, IdempotencyService idempotency,
            SubmissionValidator forms, ClarificationService clarifications, AssessmentAcceptanceService acceptance,
            PolicyParameters policy, Clock clock) {
        this.applications = applications;
        this.assessments = assessments;
        this.companies = companies;
        this.catalog = catalog;
        this.currentUsers = currentUsers;
        this.idempotency = idempotency;
        this.forms = forms;
        this.clarifications = clarifications;
        this.acceptance = acceptance;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public StoredResponse submit(UUID applicationId, SubmitApplicationRequest request, UUID key, UUID requestId) {
        var user = currentUsers.getCurrentUser();
        if (!applications.existsByIdAndCompanyId(applicationId, user.companyId())) {
            throw new ApplicationNotFoundException();
        }
        var scope = new IdempotencyScope(user.userId(), user.companyId(), "applications.submit.v1", applicationId.toString(), key);
        IdempotencyResult result = idempotency.begin(scope, request, Duration.ofMinutes(1));
        if (result instanceof IdempotencyResult.Replay replay) {
            return replay.response();
        }
        var application = applications.findForUpdate(applicationId, user.companyId()).orElseThrow(ApplicationNotFoundException::new);
        if (application.getStatus() == ApplicationStatus.CHECKING) {
            throw ApplicationConflictException.alreadySubmitted();
        }
        if (application.getStatus() != ApplicationStatus.DRAFT && application.getStatus() != ApplicationStatus.NEEDS_INFO) {
            throw ApplicationConflictException.invalidState();
        }
        if (!application.getVersion().equals(request.expectedVersion())) {
            throw ApplicationConflictException.versionConflict();
        }
        var product = catalog.findActiveProductForSubmission(application.getProductCode())
                .orElseThrow(() -> new ApiValidationException(List.of(new FieldIssue("productCode", "INVALID_VALUE", "Продукт недоступен"))));
        if (!product.terms().version().equals(request.expectedProductTermsVersion())) {
            throw ApplicationConflictException.termsChanged(product.terms().version());
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var company = companies.findById(user.companyId()).orElseThrow(() -> new IllegalStateException("Компания сессии отсутствует"));
        CreditAssessment previous = application.getCurrentAssessmentId() == null ? null
                : assessments.findByIdAndApplicationId(application.getCurrentAssessmentId(), applicationId).orElseThrow();
        List<InflowConfirmation> confirmations = new ArrayList<>();
        if (previous != null) {
            var inflow = application.getForm().averageMonthlyInflow();
            previous.getInputSnapshot().confirmations().stream()
                    .filter(value -> value.field().equals("form.averageMonthlyInflow") && inflow != null
                            && value.value().compareTo(inflow) == 0 && value.period().equals(application.getReportingPeriod()))
                    .forEach(confirmations::add);
        }
        var productSnapshot = new ProductSnapshot(product.code(), product.name(), product.description(), product.terms());
        var companySnapshot = new CompanySnapshot(company.getId(), company.getLegalForm(), company.getCountryCode(),
                company.getRegistrationDate(), company.hasSettlementAccount(), company.hasRegularInflowLast3Months());
        AssessmentInput input = new AssessmentInput(2, productSnapshot, application.getForm(), application.getCalculationDate(),
                application.getReportingPeriod(), now.atZone(ZoneOffset.UTC).toLocalDate(), companySnapshot, confirmations);
        var issues = forms.validate(input.submissionContext());
        if (!issues.isEmpty()) {
            throw new ApiValidationException(issues);
        }
        var questions = clarifications.currentQuestions(application);
        confirmations.addAll(clarifications.validateAnswers(application, questions, request.clarificationResponses()));
        input = new AssessmentInput(2, input.product(), input.form(), input.calculationDate(), input.reportingPeriod(),
                input.asOfDate(), input.company(), confirmations.stream().distinct().toList());
        CreditAssessment assessment = new CreditAssessment(applicationId, previous == null ? 1 : previous.getAttemptNo() + 1,
                previous == null ? null : previous.getId(), input, policy, now);
        StoredResponse response = acceptance.accept(application, assessment, (IdempotencyResult.Claim) result, user.userId(), requestId, now);
        clarifications.recordAnswers(application, questions, request.clarificationResponses(), user.userId(), assessment.getId(), now);
        return response;
    }
}
