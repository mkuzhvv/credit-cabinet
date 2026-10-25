package com.creditcabinet.credit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.creditcabinet.common.json.MoneyJson;
import com.creditcabinet.credit.model.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record ApplicationSummary(UUID id, String productCode, String productName, ApplicationStatus status,
        @MoneyJson @Schema(type = "string", nullable = true) BigDecimal amount, Integer termMonths, long version, Instant updatedAt) {
}
