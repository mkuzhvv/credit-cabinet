package com.creditcabinet.catalog.api;

import com.creditcabinet.catalog.model.ProductTerms;

public record ProductView(String code, String name, String description, ProductTerms terms) {
}
