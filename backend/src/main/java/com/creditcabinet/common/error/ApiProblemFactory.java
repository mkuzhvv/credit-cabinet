package com.creditcabinet.common.error;

import java.net.URI;
import java.util.Locale;

import com.creditcabinet.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemFactory {

    public ProblemDetail create(HttpStatusCode status, String code, String title, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("urn:credit-cabinet:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", RequestIdFilter.getRequestId(request));
        return problem;
    }
}
