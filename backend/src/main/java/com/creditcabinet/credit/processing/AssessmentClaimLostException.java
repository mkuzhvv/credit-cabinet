package com.creditcabinet.credit.processing;

public class AssessmentClaimLostException extends RuntimeException {

    public AssessmentClaimLostException() {
        super("Попытка больше не принадлежит этому запуску");
    }
}
