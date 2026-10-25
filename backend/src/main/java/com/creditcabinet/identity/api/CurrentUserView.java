package com.creditcabinet.identity.api;

import java.time.LocalDate;
import java.util.UUID;

public record CurrentUserView(UserView user, CompanyView company, boolean demo) {

    public record UserView(UUID id, String displayName) {
    }

    public record CompanyView(UUID id, String name, String legalForm, LocalDate registrationDate,
            String countryCode, boolean hasSettlementAccount, boolean hasRegularInflowLast3Months) {
    }
}
