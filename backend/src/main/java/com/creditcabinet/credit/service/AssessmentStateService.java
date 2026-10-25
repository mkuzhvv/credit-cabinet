package com.creditcabinet.credit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.creditcabinet.credit.credithistory.CreditHistoryReport;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.ClarificationRequirement;
import com.creditcabinet.credit.decision.RiskEstimate;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.AssessmentResult;
import com.creditcabinet.credit.model.AssessmentResult.ProcessingFailure;
import com.creditcabinet.credit.model.CreditApplication;
import com.creditcabinet.credit.model.CreditClarification;
import com.creditcabinet.credit.model.CreditDecision;
import com.creditcabinet.credit.persistence.ApplicationEventRepository;
import com.creditcabinet.credit.persistence.AssessmentClaimRepository;
import com.creditcabinet.credit.persistence.AssessmentProcessingRepository;
import com.creditcabinet.credit.persistence.CreditApplicationRepository;
import com.creditcabinet.credit.persistence.CreditAssessmentRepository;
import com.creditcabinet.credit.persistence.CreditClarificationRepository;
import com.creditcabinet.credit.processing.AssessmentClaim;
import com.creditcabinet.credit.processing.AssessmentClaimLostException;
import com.creditcabinet.credit.processing.AssessmentWork;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentStateService {

    private final CreditApplicationRepository applications;
    private final CreditAssessmentRepository assessments;
    private final CreditClarificationRepository questions;
    private final AssessmentClaimRepository claims;
    private final AssessmentProcessingRepository processing;
    private final ApplicationEventRepository events;
    private final EntityManager entities;
    private final Clock clock;

    public AssessmentStateService(CreditApplicationRepository applications, CreditAssessmentRepository assessments,
            CreditClarificationRepository questions, AssessmentClaimRepository claims, AssessmentProcessingRepository processing,
            ApplicationEventRepository events, EntityManager entities, Clock clock) {
        this.applications = applications;
        this.assessments = assessments;
        this.questions = questions;
        this.claims = claims;
        this.processing = processing;
        this.events = events;
        this.entities = entities;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AssessmentWork> load(AssessmentClaim claim) {
        var application = lockCurrent(claim, false);
        if (application.isEmpty()) {
            return Optional.empty();
        }
        var assessment = assessments.findByIdAndApplicationId(claim.assessmentId(), claim.applicationId()).orElseThrow();
        var input = assessment.getInputSnapshot();
        if (input.schemaVersion() != 2 || !input.company().id().equals(application.get().getCompanyId())
                || !input.product().code().equals(application.get().getProductCode())) {
            throw new IllegalStateException("Снимок попытки не соответствует заявке");
        }
        var work = new AssessmentWork(input, assessment.getPolicyParameters(), assessment.getCreditHistorySnapshot(), requestId(claim));
        entities.detach(assessment);
        return Optional.of(work);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreditHistoryReport storeHistory(AssessmentClaim claim, CreditHistoryReport report) {
        if (lockCurrent(claim, false).isEmpty()) {
            throw new AssessmentClaimLostException();
        }
        return processing.storeHistory(claim, report).orElseThrow(AssessmentClaimLostException::new);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishDecision(AssessmentClaim claim, CreditDecision decision, RiskEstimate risk) {
        var application = lockCurrent(claim, false);
        if (application.isEmpty()) {
            return false;
        }
        ApplicationStatus outcome = decision.type() == CreditDecision.Type.APPROVED ? ApplicationStatus.APPROVED : ApplicationStatus.DECLINED;
        String reason = decision.reasons().isEmpty() ? "RULES_PASSED" : decision.reasons().getFirst().code();
        return finish(application.get(), claim, outcome, new AssessmentResult(1, decision, List.of(), null), risk, reason, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requestInformation(AssessmentClaim claim, ClarificationRequirement requirement) {
        var application = lockCurrent(claim, false);
        if (application.isEmpty()) {
            return false;
        }
        CreditClarification question = questions.saveAndFlush(new CreditClarification(claim.applicationId(), claim.assessmentId(), requirement, now()));
        return finish(application.get(), claim, ApplicationStatus.NEEDS_INFO,
                new AssessmentResult(1, null, List.of(question.getId()), null), null, requirement.code(), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean technicalHold(AssessmentClaim claim, String code, String message) {
        var application = lockCurrent(claim, false);
        if (application.isEmpty()) {
            return false;
        }
        return finish(application.get(), claim, ApplicationStatus.TECHNICAL_HOLD,
                failure(code, message), null, code, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean interruptExpired(AssessmentClaim claim) {
        var application = lockCurrent(claim, true);
        if (application.isEmpty()) {
            return false;
        }
        String code = "PROCESSING_INTERRUPTED";
        return finish(application.get(), claim, ApplicationStatus.TECHNICAL_HOLD,
                failure(code, "Не удалось завершить проверку после повторных запусков. Повторите рассмотрение"), null, code, true);
    }

    private Optional<CreditApplication> lockCurrent(AssessmentClaim claim, boolean expired) {
        var application = applications.findForProcessing(claim.applicationId());
        if (application.isEmpty() || application.get().getStatus() != ApplicationStatus.CHECKING
                || !claim.assessmentId().equals(application.get().getCurrentAssessmentId())) {
            return Optional.empty();
        }
        return (expired ? claims.lockExpired(claim) : claims.lockOwned(claim)).isPresent() ? application : Optional.empty();
    }

    private boolean finish(CreditApplication application, AssessmentClaim claim, ApplicationStatus outcome,
            AssessmentResult result, RiskEstimate risk, String reason, boolean expired) {
        Instant now = now();
        application.finishAssessment(outcome, now);
        applications.flush();
        if (!processing.finish(claim, outcome, result, risk, now, expired)) {
            throw new AssessmentClaimLostException();
        }
        events.recordTransition(application.getId(), application.getVersion(), claim.assessmentId(), ApplicationStatus.CHECKING,
                outcome, reason, null, requestId(claim), now);
        return true;
    }

    private AssessmentResult failure(String code, String message) {
        return new AssessmentResult(1, null, List.of(), new ProcessingFailure(code, message, now()));
    }

    private UUID requestId(AssessmentClaim claim) {
        return events.findSubmissionRequestId(claim.assessmentId()).orElseGet(UUID::randomUUID);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
