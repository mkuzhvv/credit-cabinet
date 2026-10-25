package com.creditcabinet.credit.service;

public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException() {
        super("Заявка не найдена");
    }
}
