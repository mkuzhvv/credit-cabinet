package com.creditcabinet.common.idempotency;

public class IdempotencyConflictException extends RuntimeException {

    private final String code;

    private IdempotencyConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static IdempotencyConflictException keyReused() {
        return new IdempotencyConflictException("IDEMPOTENCY_KEY_REUSED", "Ключ уже использован с другими параметрами запроса");
    }

    public static IdempotencyConflictException operationInProgress() {
        return new IdempotencyConflictException("OPERATION_IN_PROGRESS", "Запрос уже выполняется. Повторите его позже с тем же ключом");
    }

    public static IdempotencyConflictException claimLost() {
        return new IdempotencyConflictException("OPERATION_IN_PROGRESS", "Срок выполнения истёк. Повторите запрос с тем же ключом");
    }

    public String getCode() {
        return code;
    }
}
