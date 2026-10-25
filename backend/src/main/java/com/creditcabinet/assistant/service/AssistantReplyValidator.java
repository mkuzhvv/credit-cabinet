package com.creditcabinet.assistant.service;

import java.util.regex.Pattern;

import com.creditcabinet.assistant.integration.AssistantGeneration;
import com.creditcabinet.assistant.model.AssistantContext;
import org.springframework.stereotype.Component;

@Component
public class AssistantReplyValidator {

    private static final Pattern HTML_TAG = Pattern.compile("<\\s*/?\\s*[a-zA-Z][^>]*>");

    public void validate(AssistantContext input, AssistantGeneration result) {
        if (result == null || result.reply() == null || result.metadata() == null) {
            throw AssistantException.invalidResponse();
        }
        var reply = result.reply();
        if (reply.answer() == null || reply.answer().isBlank() || reply.answer().length() > 3000
                || HTML_TAG.matcher(reply.answer()).find()
                || reply.recommendedProductCode() != null && input.context().catalog().stream()
                        .noneMatch(product -> product.code().equals(reply.recommendedProductCode()))) {
            throw AssistantException.invalidResponse();
        }
        var metadata = result.metadata();
        if (!validLabel(metadata.model()) || !validLabel(metadata.modelDigest()) || !validLabel(metadata.promptVersion())
                || metadata.parameters().size() > 20
                || metadata.parameters().entrySet().stream().anyMatch(entry -> !validLabel(entry.getKey()) || !validLabel(entry.getValue()))) {
            throw AssistantException.invalidResponse();
        }
    }

    private boolean validLabel(String value) {
        return value != null && !value.isBlank() && value.length() <= 256;
    }
}
