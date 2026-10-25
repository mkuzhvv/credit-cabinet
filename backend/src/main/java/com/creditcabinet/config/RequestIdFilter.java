package com.creditcabinet.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static UUID getRequestId(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof UUID id) {
            return id;
        }
        UUID id = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID id = getRequestId(request);
        response.setHeader(HEADER, id.toString());
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/")) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }

        String previousId = MDC.get("requestId");
        MDC.put("requestId", id.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("HTTP {} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
            if (previousId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousId);
            }
        }
    }
}
