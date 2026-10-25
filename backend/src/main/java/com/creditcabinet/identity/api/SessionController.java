package com.creditcabinet.identity.api;

import com.creditcabinet.identity.service.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Session")
public class SessionController {

    private final IdentityService identity;
    public SessionController(IdentityService identity) {
        this.identity = identity;
    }

    @GetMapping("/csrf")
    @Operation(operationId = "getCsrfToken", summary = "Получить CSRF-токен")
    public CsrfView csrf(@Parameter(hidden = true) CsrfToken token) {
        return new CsrfView(token.getToken(), token.getHeaderName());
    }

    @GetMapping("/me")
    @Operation(operationId = "getCurrentUser", summary = "Получить пользователя и компанию")
    public CurrentUserView me() {
        return identity.getCurrentUser();
    }
}
