package com.creditcabinet.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RequestHasher {

    private final JsonMapper mapper;

    public RequestHasher(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(@Nullable Object request) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(mapper.valueToTree(request), canonical);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void appendCanonical(JsonNode node, StringBuilder target) {
        if (node.isObject()) {
            target.append('{');
            boolean first = true;
            for (String name : node.propertyNames().stream().sorted().toList()) {
                if (!first) {
                    target.append(',');
                }
                target.append(mapper.writeValueAsString(name)).append(':');
                appendCanonical(node.get(name), target);
                first = false;
            }
            target.append('}');
        } else if (node.isArray()) {
            target.append('[');
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) {
                    target.append(',');
                }
                appendCanonical(element, target);
                first = false;
            }
            target.append(']');
        } else if (node.isNumber()) {
            target.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else {
            target.append(mapper.writeValueAsString(node));
        }
    }
}
