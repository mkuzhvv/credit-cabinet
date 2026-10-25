package com.creditcabinet.common.idempotency;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import tools.jackson.databind.JsonNode;

public record StoredResponse(int status, JsonNode body, Map<String, String> headers) {

    public StoredResponse {
        Assert.isTrue(status >= 200 && status < 300, "Only successful responses can be stored");
        Assert.isTrue(body != null && body.isObject(), "Response body must be a JSON object");
        body = body.deepCopy();
        Map<String, String> allowed = new HashMap<>();
        headers.forEach((name, value) -> {
            if (name.equalsIgnoreCase(HttpHeaders.LOCATION)) {
                allowed.put(HttpHeaders.LOCATION, value);
            } else if (name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) {
                allowed.put(HttpHeaders.CONTENT_TYPE, value);
            }
        });
        headers = Map.copyOf(allowed);
    }

    public ResponseEntity<JsonNode> toResponseEntity() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(responseHeaders::set);
        responseHeaders.setCacheControl("no-store");
        return new ResponseEntity<>(body, responseHeaders, status);
    }
}
