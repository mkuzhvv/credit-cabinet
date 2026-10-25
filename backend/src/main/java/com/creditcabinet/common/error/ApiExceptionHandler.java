package com.creditcabinet.common.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.creditcabinet.common.idempotency.IdempotencyConflictException;
import com.creditcabinet.config.RequestIdFilter;
import com.creditcabinet.credit.service.ApplicationConflictException;
import com.creditcabinet.credit.service.ApplicationNotFoundException;
import com.creditcabinet.credit.service.ClarificationException;
import com.creditcabinet.assistant.service.AssistantException;
import com.creditcabinet.assistant.service.AssistantNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final Pattern FIELD_PATH = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*");
    private final ApiProblemFactory problems;

    public ApiExceptionHandler(ApiProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> malformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Неверный формат запроса", request);
        if (ex.getCause() instanceof JacksonException jackson) {
            String field = jackson.getPath().stream().map(JacksonException.Reference::getPropertyName)
                    .filter(Objects::nonNull).collect(Collectors.joining("."));
            addFieldIssues(problem, List.of(new FieldIssue(field, "INVALID_FORMAT", "Проверьте формат значения поля")));
        }
        return response(problem, request);
    }

    @ExceptionHandler({TypeMismatchException.class, ServletRequestBindingException.class})
    public ResponseEntity<ProblemDetail> malformedParameters(HttpServletRequest request) {
        return response(problems.create(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Неверный формат запроса", request), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Проверьте поля", request);
        List<FieldIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> validationIssue(error.getField(), error)).toList();
        addFieldIssues(problem, issues);
        return response(problem, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> parameterValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        if (ex.isForReturnValue()) {
            return unexpected(ex, request);
        }
        ProblemDetail problem = problems.create(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Проверьте поля", request);
        List<FieldIssue> issues = new ArrayList<>();
        for (var result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                for (var error : errors.getFieldErrors()) {
                    issues.add(validationIssue(error.getField(), error));
                }
            } else {
                for (var error : result.getResolvableErrors()) {
                    issues.add(validationIssue(result.getMethodParameter().getParameterName(), error));
                }
            }
        }
        addFieldIssues(problem, issues);
        return response(problem, request);
    }

    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ProblemDetail> invalidFields(ApiValidationException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", ex.getMessage(), request);
        addFieldIssues(problem, ex.getIssues());
        return response(problem, request);
    }

    @ExceptionHandler(ApplicationConflictException.class)
    public ResponseEntity<ProblemDetail> applicationConflict(ApplicationConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), request);
        if (ex.getCurrentProductTermsVersion() != null) {
            problem.setProperty("currentProductTermsVersion", ex.getCurrentProductTermsVersion());
        }
        return response(problem, request);
    }

    @ExceptionHandler(ClarificationException.class)
    public ResponseEntity<ProblemDetail> clarification(ClarificationException ex, HttpServletRequest request) {
        HttpStatus status = ex.getCode().equals("CLARIFICATION_STALE") ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_CONTENT;
        return response(problems.create(status, ex.getCode(), ex.getMessage(), request), request);
    }

    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<ProblemDetail> assistant(AssistantException ex, HttpServletRequest request) {
        return response(problems.create(ex.getStatus(), ex.getCode(), ex.getMessage(), request), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> concurrentUpdate(HttpServletRequest request) {
        ApplicationConflictException conflict = ApplicationConflictException.versionConflict();
        return response(problems.create(HttpStatus.CONFLICT, conflict.getCode(), conflict.getMessage(), request), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> idempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        return response(problems.create(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), request), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class, ApplicationNotFoundException.class, AssistantNotFoundException.class})
    public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return response(problems.create(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ресурс не найден", request), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Метод запроса не поддерживается", request);
        return response(problem, ex.getHeaders(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Формат ответа не поддерживается", request);
        return response(problem, ex.getHeaders(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Формат тела запроса не поддерживается", request);
        return response(problem, ex.getHeaders(), request);
    }

    @ExceptionHandler({Exception.class, ConversionNotSupportedException.class, MissingPathVariableException.class})
    public ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Не удалось обработать HTTP-запрос", ex);
        return response(problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Не удалось выполнить запрос", request), request);
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem, HttpServletRequest request) {
        return response(problem, HttpHeaders.EMPTY, request);
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem, HttpHeaders headers, HttpServletRequest request) {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        responseHeaders.setCacheControl("no-store");
        responseHeaders.set(RequestIdFilter.HEADER, RequestIdFilter.getRequestId(request).toString());
        return new ResponseEntity<>(problem, responseHeaders, problem.getStatus());
    }

    private void addFieldIssues(ProblemDetail problem, List<FieldIssue> issues) {
        List<FieldIssue> validIssues = issues.stream().filter(issue -> validPath(issue.field())).limit(30).toList();
        if (!validIssues.isEmpty()) {
            problem.setProperty("errors", validIssues);
        }
    }

    private FieldIssue validationIssue(@Nullable String field, MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        String constraint = codes == null || codes.length == 0 ? "" : codes[codes.length - 1];
        String code = switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Positive" -> "POSITIVE_REQUIRED";
            case "PositiveOrZero" -> "NON_NEGATIVE_REQUIRED";
            case "Size" -> "INVALID_LENGTH";
            case "Min", "Max", "DecimalMin", "DecimalMax" -> "OUT_OF_RANGE";
            default -> "INVALID_VALUE";
        };
        String message = error.getDefaultMessage();
        if (message == null || message.isBlank()) {
            message = "Проверьте значение поля";
        }
        return new FieldIssue(field == null ? "" : field, code, message.substring(0, Math.min(message.length(), 1000)));
    }

    private boolean validPath(String field) {
        return field.length() <= 120 && FIELD_PATH.matcher(field).matches();
    }
}
