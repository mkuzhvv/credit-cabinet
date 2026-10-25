package com.creditcabinet.assistant.service;

import org.springframework.http.HttpStatus;

public class AssistantException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    private AssistantException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public static AssistantException busy() {
        return new AssistantException("CONVERSATION_BUSY", HttpStatus.CONFLICT, "Помощник уже отвечает в этом диалоге", null);
    }

    public static AssistantException unavailable() {
        return unavailable(null);
    }

    public static AssistantException unavailable(Throwable cause) {
        return new AssistantException("ASSISTANT_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Помощник сейчас недоступен", cause);
    }

    public static AssistantException invalidResponse() {
        return new AssistantException("ASSISTANT_INVALID_RESPONSE", HttpStatus.BAD_GATEWAY, "Помощник вернул некорректный ответ", null);
    }

    public static AssistantException timeout(Throwable cause) {
        return new AssistantException("ASSISTANT_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "Время ожидания ответа помощника истекло", cause);
    }

    public String getCode() {
        return code;
    }
    public HttpStatus getStatus() {
        return status;
    }
}
