package com.creditcabinet.credit.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import com.creditcabinet.common.error.ApiValidationException;
import com.creditcabinet.common.error.FieldIssue;
import com.creditcabinet.credit.api.SubmitApplicationRequest.ClarificationResponse;
import com.creditcabinet.credit.decision.CreditDecisionPolicy.InflowConfirmation;
import com.creditcabinet.credit.model.ApplicationForm;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.model.CreditApplication;
import com.creditcabinet.credit.model.CreditClarification;
import com.creditcabinet.credit.model.CreditClarification.ResponseType;
import com.creditcabinet.credit.persistence.CreditClarificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ClarificationService {

    private final CreditClarificationRepository questions;

    public ClarificationService(CreditClarificationRepository questions) {
        this.questions = questions;
    }

    public List<CreditClarification> currentQuestions(CreditApplication application) {
        return application.getCurrentAssessmentId() == null ? List.of()
                : questions.findOpen(application.getId(), application.getCurrentAssessmentId());
    }

    public List<InflowConfirmation> validateAnswers(CreditApplication application, List<CreditClarification> current,
            List<ClarificationResponse> responses) {
        if (application.getStatus() == ApplicationStatus.DRAFT) {
            if (!responses.isEmpty()) {
                throw ClarificationException.notExpected();
            }
            return List.of();
        }
        if (current.isEmpty()) {
            throw ClarificationException.stale();
        }
        var byId = new HashMap<UUID, ClarificationResponse>();
        for (ClarificationResponse response : responses) {
            if (current.stream().noneMatch(question -> question.getId().equals(response.clarificationId()))) {
                throw ClarificationException.stale();
            }
            if (byId.put(response.clarificationId(), response) != null) {
                throw ClarificationException.conflict();
            }
        }
        if (byId.size() != current.size()) {
            throw ClarificationException.required();
        }
        List<InflowConfirmation> confirmations = new ArrayList<>();
        for (CreditClarification question : current) {
            validateQuestion(application, question);
            var value = application.getForm().averageMonthlyInflow();
            ResponseType response = byId.get(question.getId()).response();
            if (value == null || (response == ResponseType.CONFIRM_VALUE && value.signum() != 0)
                    || (response == ResponseType.VALUE_CORRECTED && value.signum() <= 0)) {
                throw ClarificationException.conflict();
            }
            if (response == ResponseType.CONFIRM_VALUE) {
                confirmations.add(new InflowConfirmation(question.getFieldCode(), value, application.getReportingPeriod()));
            }
        }
        return List.copyOf(confirmations);
    }

    public void recordAnswers(CreditApplication application, List<CreditClarification> current,
            List<ClarificationResponse> responses, UUID userId, UUID assessmentId, Instant now) {
        for (CreditClarification question : current) {
            ClarificationResponse response = responses.stream()
                    .filter(answer -> answer.clarificationId().equals(question.getId())).findFirst().orElseThrow();
            question.answer(response.response(), response.comment(), application.getForm().averageMonthlyInflow(), userId, assessmentId, now);
        }
    }

    public void validateFormUpdate(CreditApplication application, ApplicationForm replacement) {
        List<CreditClarification> current = currentQuestions(application);
        if (current.isEmpty()) {
            throw ClarificationException.stale();
        }
        current.forEach(question -> validateQuestion(application, question));
        ApplicationForm allowed = application.getForm().withAverageMonthlyInflow(replacement.averageMonthlyInflow());
        if (!allowed.equals(replacement)) {
            throw new ApiValidationException(List.of(new FieldIssue("form", "NOT_EDITABLE",
                    "В этом уточнении можно изменить только среднемесячные поступления")));
        }
    }

    private void validateQuestion(CreditApplication application, CreditClarification question) {
        if (!"ZERO_INFLOW_CONFIRMATION".equals(question.getRuleCode()) || !"form.averageMonthlyInflow".equals(question.getFieldCode())
                || question.getObservedValue().signum() != 0
                || !question.getReportingPeriodStart().equals(application.getReportingPeriod().startDate())
                || !question.getReportingPeriodEnd().equals(application.getReportingPeriod().endDate())) {
            throw ClarificationException.stale();
        }
    }
}
