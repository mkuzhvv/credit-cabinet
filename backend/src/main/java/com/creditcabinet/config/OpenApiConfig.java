package com.creditcabinet.config;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI backendOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Credit Cabinet — Backend API")
                .version("0.4.0")
                .description("Описание реализованных операций. Нормативный контракт: docs/contracts/backend.openapi.yaml."))
                .components(new Components()
                        .addSecuritySchemes("SessionCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name("JSESSIONID"))
                        .addSecuritySchemes("CsrfToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN")))
                .addSecurityItem(new SecurityRequirement().addList("SessionCookie"));
    }

    @Bean
    OpenApiCustomizer sessionSecurityDocumentation() {
        return api -> {
            var csrf = api.getPaths().get("/api/v1/csrf");
            if (csrf != null) {
                csrf.getGet().setSecurity(List.of());
            }
            var demo = api.getPaths().get("/api/v1/demo/session");
            if (demo != null) {
                demo.getPost().setSecurity(List.of(new SecurityRequirement()
                        .addList("SessionCookie").addList("CsrfToken")));
            }
            var applications = api.getPaths().get("/api/v1/applications");
            if (applications != null && applications.getPost() != null) {
                applications.getPost().setSecurity(List.of(new SecurityRequirement()
                        .addList("SessionCookie").addList("CsrfToken")));
            }
            var form = api.getPaths().get("/api/v1/applications/{applicationId}/form");
            if (form != null && form.getPut() != null) {
                form.getPut().setSecurity(List.of(new SecurityRequirement()
                        .addList("SessionCookie").addList("CsrfToken")));
            }
        };
    }
}
