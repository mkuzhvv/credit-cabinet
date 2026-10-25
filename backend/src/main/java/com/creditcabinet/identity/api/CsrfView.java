package com.creditcabinet.identity.api;

public record CsrfView(String token, String headerName) {
}
