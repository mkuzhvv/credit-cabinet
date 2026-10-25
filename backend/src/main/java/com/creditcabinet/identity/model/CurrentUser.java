package com.creditcabinet.identity.model;

import java.util.UUID;

public record CurrentUser(UUID userId, UUID companyId) {
}
