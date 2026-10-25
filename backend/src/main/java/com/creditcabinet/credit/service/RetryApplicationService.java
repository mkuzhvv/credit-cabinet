package com.creditcabinet.credit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.creditcabinet.common.idempotency.IdempotencyResult;
import com.creditcabinet.common.idempotency.IdempotencyScope;
import com.creditcabinet.common.idempotency.IdempotencyService;
import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.credit.api.RetryApplicationRequest;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.CreditAssessment.ProcessingStatus;
import com.creditcabinet.credit.persistence.CreditApplicationRepository;
import com.creditcabinet.credit.persistence.CreditAssessmentRepository;
import com.creditcabinet.identity.service.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetryApplicationService {

    private final CreditApplicationRepository applications;
    private final CreditAssessmentRepository assessments;
    private final CurrentUserProvider currentUsers;
    private final IdempotencyService idempotency;
    private final AssessmentAcceptanceService acceptance;
    private final Clock clock;

    public RetryApplicationService(CreditApplicationRepository applications, CreditAssessmentRepository assessments,
            CurrentUserProvider currentUsers, IdempotencyService idempotency, AssessmentAcceptanceService acceptance, Clock clock) {
        this.applications = applications;
        this.assessments = assessments;
        this.currentUsers = currentUsers;
        this.idempotency = idempotency;
        this.acceptance = acceptance;
        this.clock = clock;
    }

    @Transactional
    public StoredResponse retry(UUID applicationId, RetryApplicationRequest request, UUID key, UUID requestId) {
        var user = currentUsers.getCurrentUser();
        if (!applications.existsByIdAndCompanyId(applicationId, user.companyId())) {
            throw new ApplicationNotFoundException();
        }
        var scope = new IdempotencyScope(user.userId(), user.companyId(), "applications.retry.v1", applicationId.toString(), key);
        IdempotencyResult result = idempotency.begin(scope, request, Duration.ofMinutes(1));
        if (result instanceof IdempotencyResult.Replay replay) {
            return replay.response();
        }
        var application = applications.findForUpdate(applicationId, user.companyId()).orElseThrow(ApplicationNotFoundException::new);
        if (application.getStatus() != ApplicationStatus.TECHNICAL_HOLD) {
            throw ApplicationConflictException.invalidState();
        }
        if (!application.getVersion().equals(request.expectedVersion())) {
            throw ApplicationConflictException.versionConflict();
        }
        var previous = assessments.findByIdAndApplicationId(application.getCurrentAssessmentId(), applicationId).orElseThrow();
        if (previous.getProcessingStatus() != ProcessingStatus.FINISHED || previous.getOutcome() != ApplicationStatus.TECHNICAL_HOLD) {
            throw new IllegalStateException("Предыдущая попытка не завершена технической ошибкой");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return acceptance.accept(application, previous.retry(now), (IdempotencyResult.Claim) result, user.userId(), requestId, now);
    }
}
