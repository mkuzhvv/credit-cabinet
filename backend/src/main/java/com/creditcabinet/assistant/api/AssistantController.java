package com.creditcabinet.assistant.api;

import java.util.UUID;

import com.creditcabinet.assistant.service.AssistantService;
import com.creditcabinet.assistant.service.AssistantStateService;
import com.creditcabinet.config.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/assistant/conversations")
@Tag(name = "Assistant")
public class AssistantController {

    private final AssistantStateService state;
    private final AssistantService assistant;

    public AssistantController(AssistantStateService state, AssistantService assistant) {
        this.state = state;
        this.assistant = assistant;
    }

    @PostMapping
    @Operation(operationId = "createAssistantConversation", summary = "Создать диалог помощника",
            security = {@SecurityRequirement(name = "SessionCookie"), @SecurityRequirement(name = "CsrfToken")})
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = ConversationView.class)))
    public ResponseEntity<JsonNode> create(@RequestHeader("Idempotency-Key") UUID key) {
        return state.create(key).toResponseEntity();
    }

    @GetMapping("/{conversationId}/messages")
    @Operation(operationId = "listAssistantMessages", summary = "Получить историю диалога")
    public MessagePage history(@PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(9007199254740991L) long afterSequence,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return state.history(conversationId, afterSequence, limit);
    }

    @PostMapping("/{conversationId}/messages")
    @Operation(operationId = "sendAssistantMessage", summary = "Получить ответ помощника",
            security = {@SecurityRequirement(name = "SessionCookie"), @SecurityRequirement(name = "CsrfToken")})
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MessageExchange.class)))
    public ResponseEntity<JsonNode> send(@PathVariable UUID conversationId, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody SendMessageRequest input, HttpServletRequest request) {
        return assistant.send(conversationId, input, key, RequestIdFilter.getRequestId(request)).toResponseEntity();
    }
}
