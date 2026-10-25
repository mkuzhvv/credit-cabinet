package com.creditcabinet.credit.api;

import java.util.List;

public record ApplicationPage(List<ApplicationSummary> items, int page, int size, long totalElements, int totalPages) {
}
