package com.creditcabinet.common.idempotency;

import java.util.UUID;

import org.springframework.util.Assert;

public record IdempotencyScope(UUID userId, UUID companyId, String operation, String resourceScope, UUID key) {

    public IdempotencyScope {
        Assert.notNull(userId, "userId is required");
        Assert.notNull(companyId, "companyId is required");
        Assert.hasText(operation, "operation is required");
        Assert.isTrue(operation.length() <= 64, "operation must fit varchar(64)");
        Assert.hasText(resourceScope, "resourceScope is required");
        Assert.isTrue(resourceScope.length() <= 128, "resourceScope must fit varchar(128)");
        Assert.notNull(key, "Idempotency-Key is required");
    }
}
