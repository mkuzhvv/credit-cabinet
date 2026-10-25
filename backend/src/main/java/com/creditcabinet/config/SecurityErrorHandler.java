package com.creditcabinet.config;

import java.io.IOException;

import com.creditcabinet.common.error.ApiProblemFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ApiProblemFactory problems;
    private final JsonMapper json;

    public SecurityErrorHandler(ApiProblemFactory problems, JsonMapper json) {
        this.problems = problems;
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "Создайте демосессию", request, response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ex instanceof CsrfException) {
            write(HttpStatus.FORBIDDEN, "CSRF_INVALID", "Обновите защитный токен запроса", request, response);
        } else {
            write(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Нет доступа к операции", request, response);
        }
    }

    private void write(HttpStatus status, String code, String title,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = problems.create(status, code, title, request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), problem);
    }
}
