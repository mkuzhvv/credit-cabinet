package com.creditcabinet.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    @Bean
    HibernatePropertiesCustomizer databaseJson(JsonMapper mapper) {
        return properties -> properties.put("hibernate.type.json_format_mapper", new Jackson3JsonFormatMapper(mapper));
    }

    @Bean
    JsonMapperBuilderCustomizer strictRequestJson() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
