package com.creditcabinet.credit.service;

public class ApplicationConflictException extends RuntimeException {

    private final String code;

    private ApplicationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ApplicationConflictException versionConflict() {
        return new ApplicationConflictException("VERSION_CONFLICT", "Заявка уже изменена. Обновите её перед сохранением");
    }

    public static ApplicationConflictException invalidState() {
        return new ApplicationConflictException("INVALID_APPLICATION_STATE", "В текущем состоянии форму изменять нельзя");
    }

    public String getCode() {
        return code;
    }
}
