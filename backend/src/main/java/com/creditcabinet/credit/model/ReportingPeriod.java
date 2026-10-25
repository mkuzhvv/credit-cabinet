package com.creditcabinet.credit.model;

import java.time.LocalDate;

public record ReportingPeriod(LocalDate startDate, LocalDate endDate, int months) {
}
