package com.creditcabinet.identity.api;

import com.creditcabinet.identity.service.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("demo")
@RestController
@Tag(name = "Session")
public class DemoSessionController {

    private final IdentityService identity;

    public DemoSessionController(IdentityService identity) {
        this.identity = identity;
    }

    @PostMapping("/api/v1/demo/session")
    @Operation(operationId = "initializeDemoSession", summary = "Войти в подготовленный демоаккаунт")
    public CurrentUserView initialize(HttpServletRequest request, HttpServletResponse response) {
        return identity.initializeDemoSession(request, response);
    }
}
