package com.creditcabinet.credit.service;

import java.util.ArrayList;
import java.util.List;

import com.creditcabinet.common.error.ApiValidationException;
import com.creditcabinet.common.error.FieldIssue;
import com.creditcabinet.credit.model.ApplicationForm;
import org.springframework.stereotype.Component;

@Component
public class ApplicationFormValidator {

    public void validate(String productCode, ApplicationForm form) {
        List<FieldIssue> issues = findIssues(productCode, form);
        if (!issues.isEmpty()) {
            throw new ApiValidationException(issues);
        }
    }

    public List<FieldIssue> findIssues(String productCode, ApplicationForm form) {
        List<FieldIssue> issues = new ArrayList<>();
        if (!productCode.equals("LONG_TERM")) {
            rejectIfPresent(issues, "financingPurpose", form.financingPurpose());
            rejectIfPresent(issues, "projectBudget", form.projectBudget());
        }
        if (!productCode.equals("REVOLVING_LINE")) {
            rejectIfPresent(issues, "trancheTermMonths", form.trancheTermMonths());
        }
        if (!productCode.equals("GOVERNMENT_CONTRACT_LINE")) {
            rejectIfPresent(issues, "governmentContract", form.governmentContract());
        }
        return List.copyOf(issues);
    }

    private void rejectIfPresent(List<FieldIssue> issues, String field, Object value) {
        if (value != null) {
            issues.add(new FieldIssue("form." + field, "NOT_APPLICABLE", "Поле не используется для выбранного продукта"));
        }
    }
}
