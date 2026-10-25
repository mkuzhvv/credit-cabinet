package com.creditcabinet.credit.service;

public class ClarificationException extends RuntimeException {

    private final String code;

    private ClarificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ClarificationException notExpected() {
        return new ClarificationException("CLARIFICATION_NOT_EXPECTED", "Черновик не ожидает ответов на уточнения");
    }

    public static ClarificationException required() {
        return new ClarificationException("CLARIFICATION_RESPONSE_REQUIRED", "Ответьте на все текущие вопросы");
    }

    public static ClarificationException conflict() {
        return new ClarificationException("CLARIFICATION_RESPONSE_CONFLICT", "Ответы повторяются или противоречат сохранённой форме");
    }

    public static ClarificationException stale() {
        return new ClarificationException("CLARIFICATION_STALE", "Вопрос больше не актуален для этой заявки");
    }

    public String getCode() {
        return code;
    }
}
