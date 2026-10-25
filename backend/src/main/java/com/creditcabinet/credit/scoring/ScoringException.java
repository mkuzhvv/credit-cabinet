package com.creditcabinet.credit.scoring;

public class ScoringException extends RuntimeException {

    private final Code code;

    public ScoringException(Code code, String message) {
        this(code, message, null);
    }

    public ScoringException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code.name();
    }

    public enum Code {
        SCORING_UNAVAILABLE, SCORING_TIMEOUT, SCORING_INVALID_RESPONSE
    }
}
