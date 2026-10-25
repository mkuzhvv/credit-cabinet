package com.creditcabinet.common.error;

import java.util.List;

public class ApiValidationException extends RuntimeException {

    private final List<FieldIssue> issues;

    public ApiValidationException(List<FieldIssue> issues) {
        super("Проверьте поля");
        this.issues = List.copyOf(issues);
    }

    public List<FieldIssue> getIssues() {
        return issues;
    }
}
