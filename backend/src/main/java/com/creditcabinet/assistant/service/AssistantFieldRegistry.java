package com.creditcabinet.assistant.service;

import java.io.IOException;
import java.util.List;

import com.creditcabinet.assistant.model.AssistantContext.FieldDefinition;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AssistantFieldRegistry {

    private final List<Field> fields;

    public AssistantFieldRegistry(JsonMapper mapper, ResourceLoader resources) throws IOException {
        fields = List.copyOf(mapper.readValue(resources.getResource("classpath:assistant/field-definitions.json").getContentAsByteArray(),
                new TypeReference<List<Field>>() { }));
    }

    public boolean supports(String code, String productCode) {
        return fields.stream().anyMatch(field -> field.code().equals(code) && field.applies(productCode));
    }

    public List<FieldDefinition> definitions(String productCode) {
        return fields.stream().filter(field -> field.applies(productCode))
                .map(field -> new FieldDefinition(field.code(), field.label(), field.description())).toList();
    }

    private record Field(String code, String label, String description, List<String> products) {
        boolean applies(String productCode) {
            return productCode == null || products.isEmpty() || products.contains(productCode);
        }
    }
}
