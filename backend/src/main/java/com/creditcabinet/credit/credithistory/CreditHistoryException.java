package com.creditcabinet.credit.credithistory;

import java.util.Objects;

public class CreditHistoryException extends RuntimeException {

    private final Code code;

    public CreditHistoryException(Code code, String message) {
        this(code, message, null);
    }

    public CreditHistoryException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code);
    }

    public String getCode() {
        return code.name();
    }

    public enum Code {
        CREDIT_HISTORY_UNAVAILABLE,
        CREDIT_HISTORY_TIMEOUT,
        CREDIT_HISTORY_INVALID_RESPONSE
    }
}
