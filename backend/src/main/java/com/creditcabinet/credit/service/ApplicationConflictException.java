package com.creditcabinet.credit.service;

public class ApplicationConflictException extends RuntimeException {

    private final String code;
    private final String currentProductTermsVersion;

    private ApplicationConflictException(String code, String message) {
        this(code, message, null);
    }

    private ApplicationConflictException(String code, String message, String currentProductTermsVersion) {
        super(message);
        this.code = code;
        this.currentProductTermsVersion = currentProductTermsVersion;
    }

    public static ApplicationConflictException versionConflict() {
        return new ApplicationConflictException("VERSION_CONFLICT", "Заявка уже изменена. Обновите её перед сохранением");
    }

    public static ApplicationConflictException invalidState() {
        return new ApplicationConflictException("INVALID_APPLICATION_STATE", "Операция недоступна в текущем состоянии заявки");
    }

    public static ApplicationConflictException alreadySubmitted() {
        return new ApplicationConflictException("APPLICATION_ALREADY_SUBMITTED", "Заявка уже находится на рассмотрении");
    }

    public static ApplicationConflictException termsChanged(String currentVersion) {
        return new ApplicationConflictException("PRODUCT_TERMS_CHANGED", "Условия продукта изменились. Ознакомьтесь с ними перед отправкой", currentVersion);
    }

    public String getCurrentProductTermsVersion() {
        return currentProductTermsVersion;
    }

    public String getCode() {
        return code;
    }
}
