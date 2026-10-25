package com.creditcabinet.credit.service;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import com.creditcabinet.common.idempotency.IdempotencyResult;
import com.creditcabinet.common.idempotency.IdempotencyService;
import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.credit.api.ProcessingAccepted;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.CreditApplication;
import com.creditcabinet.credit.model.CreditAssessment;
import com.creditcabinet.credit.persistence.ApplicationEventRepository;
import com.creditcabinet.credit.persistence.CreditApplicationRepository;
import com.creditcabinet.credit.persistence.CreditAssessmentRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentAcceptanceService {

    private final CreditAssessmentRepository assessments;
    private final CreditApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final IdempotencyService idempotency;

    public AssessmentAcceptanceService(CreditAssessmentRepository assessments, CreditApplicationRepository applications,
            ApplicationEventRepository events, IdempotencyService idempotency) {
        this.assessments = assessments;
        this.applications = applications;
        this.events = events;
        this.idempotency = idempotency;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResponse accept(CreditApplication application, CreditAssessment assessment, IdempotencyResult.Claim claim,
            UUID userId, UUID requestId, Instant now) {
        ApplicationStatus from = application.getStatus();
        assessments.saveAndFlush(assessment);
        application.startAssessment(assessment.getId(), now);
        applications.flush();
        String reason = from == ApplicationStatus.TECHNICAL_HOLD ? "ASSESSMENT_RETRY" : "APPLICATION_SUBMITTED";
        events.recordTransition(application.getId(), application.getVersion(), assessment.getId(), from,
                ApplicationStatus.CHECKING, reason, userId, requestId, now);
        String statusUrl = "/api/v1/applications/" + application.getId();
        ProcessingAccepted body = new ProcessingAccepted(application.getId(), assessment.getId(), application.getStatus(),
                application.getVersion(), now, statusUrl, 2);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(statusUrl));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return idempotency.complete(claim, 202, body, headers);
    }
}
