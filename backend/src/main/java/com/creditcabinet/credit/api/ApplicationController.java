package com.creditcabinet.credit.api;

import java.util.UUID;

import com.creditcabinet.config.RequestIdFilter;
import com.creditcabinet.credit.model.ApplicationStatus;
import com.creditcabinet.credit.service.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final ApplicationService applications;

    public ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    @PostMapping
    @Operation(operationId = "createApplication", summary = "Создать черновик")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = ApplicationView.class)))
    public ResponseEntity<JsonNode> create(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CreateApplicationRequest input, HttpServletRequest request) {
        return applications.create(input, key, RequestIdFilter.getRequestId(request)).toResponseEntity();
    }

    @GetMapping
    @Operation(operationId = "listApplications", summary = "Получить заявки текущей компании")
    public ApplicationPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ApplicationStatus status) {
        return applications.getApplications(page, size, status);
    }

    @GetMapping("/{applicationId}")
    @Operation(operationId = "getApplication", summary = "Прочитать заявку")
    public ApplicationView get(@PathVariable UUID applicationId) {
        return applications.getApplication(applicationId);
    }

    @PutMapping("/{applicationId}/form")
    @Operation(operationId = "saveApplicationForm", summary = "Заменить содержимое формы")
    public ApplicationView save(@PathVariable UUID applicationId, @Valid @RequestBody SaveFormRequest request) {
        return applications.saveForm(applicationId, request);
    }
}
