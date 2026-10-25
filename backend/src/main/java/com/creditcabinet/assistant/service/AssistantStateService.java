package com.creditcabinet.assistant.service;

import java.net.URI;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

import com.creditcabinet.assistant.api.ChatMessage;
import com.creditcabinet.assistant.api.ConversationView;
import com.creditcabinet.assistant.api.MessageExchange;
import com.creditcabinet.assistant.api.MessagePage;
import com.creditcabinet.assistant.api.SendMessageRequest;
import com.creditcabinet.assistant.integration.AssistantGeneration;
import com.creditcabinet.assistant.model.AssistantContext.HistoryMessage;
import com.creditcabinet.assistant.model.AssistantConversation;
import com.creditcabinet.assistant.model.AssistantMessage;
import com.creditcabinet.assistant.model.GenerationMetadata;
import com.creditcabinet.assistant.persistence.AssistantConversationRepository;
import com.creditcabinet.assistant.persistence.AssistantMessageRepository;
import com.creditcabinet.common.idempotency.IdempotencyConflictException;
import com.creditcabinet.common.idempotency.IdempotencyResult;
import com.creditcabinet.common.idempotency.IdempotencyScope;
import com.creditcabinet.common.idempotency.IdempotencyService;
import com.creditcabinet.common.idempotency.StoredResponse;
import com.creditcabinet.identity.model.CurrentUser;
import com.creditcabinet.identity.service.CurrentUserProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantStateService {

    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final IdempotencyService idempotency;
    private final CurrentUserProvider currentUsers;
    private final AssistantContextFactory contexts;
    private final AssistantProperties properties;
    private final Clock clock;

    public AssistantStateService(AssistantConversationRepository conversations, AssistantMessageRepository messages,
            IdempotencyService idempotency, CurrentUserProvider currentUsers, AssistantContextFactory contexts,
            AssistantProperties properties, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.idempotency = idempotency;
        this.currentUsers = currentUsers;
        this.contexts = contexts;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoredResponse create(UUID key) {
        CurrentUser user = currentUsers.getCurrentUser();
        var scope = new IdempotencyScope(user.userId(), user.companyId(), "assistant.conversations.create.v1", "collection", key);
        var result = idempotency.begin(scope, null, properties.leaseDuration());
        if (result instanceof IdempotencyResult.Replay replay) {
            return replay.response();
        }
        var conversation = conversations.saveAndFlush(new AssistantConversation(user.userId(), user.companyId(), clock.instant()));
        var body = new ConversationView(conversation.getId(), conversation.getCreatedAt(), conversation.getUpdatedAt());
        HttpHeaders headers = jsonHeaders();
        headers.setLocation(URI.create("/api/v1/assistant/conversations/" + conversation.getId() + "/messages"));
        return idempotency.complete((IdempotencyResult.Claim) result, 201, body, headers);
    }

    @Transactional(readOnly = true)
    public MessagePage history(UUID conversationId, long afterSequence, int limit) {
        checkConversation(conversationId, currentUsers.getCurrentUser());
        var found = messages.findAfter(conversationId, afterSequence, PageRequest.of(0, limit + 1));
        var items = found.stream().limit(limit).map(this::view).toList();
        return new MessagePage(items, items.isEmpty() ? afterSequence : items.getLast().sequence(), found.size() > limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssistantReservation prepare(UUID conversationId, SendMessageRequest request, UUID key) {
        CurrentUser user = currentUsers.getCurrentUser();
        checkConversation(conversationId, user);
        contexts.checkAccess(request.context(), user);
        var scope = new IdempotencyScope(user.userId(), user.companyId(), "assistant.messages.send.v1", conversationId.toString(), key);
        var result = idempotency.begin(scope, request, properties.leaseDuration());
        if (result instanceof IdempotencyResult.Replay replay) {
            return new AssistantReservation.Replay(replay.response());
        }
        conversations.findOwnedForUpdate(conversationId, user.userId(), user.companyId()).orElseThrow(AssistantNotFoundException::new);
        UUID token = UUID.randomUUID();
        if (conversations.acquire(conversationId, user.userId(), user.companyId(), token, properties.leaseDuration().toMillis()) != 1) {
            throw AssistantException.busy();
        }
        var recent = new ArrayList<>(messages.findTop20ByConversationIdOrderBySequenceDesc(conversationId));
        Collections.reverse(recent);
        var history = recent.stream().map(message -> new HistoryMessage(message.getRole(), message.getText())).toList();
        var input = contexts.create(conversationId, request, user, history);
        return new AssistantReservation.Generation(input, (IdempotencyResult.Claim) result, token, user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoredResponse finish(AssistantReservation.Generation reservation, AssistantGeneration generation) {
        var input = reservation.input();
        var user = reservation.owner();
        checkConversation(input.conversationId(), user);
        var context = input.context();
        contexts.checkAccess(new SendMessageRequest.MessageContext(context.application() == null ? null : context.application().applicationId(),
                context.selectedProductCode(), context.currentFieldCode()), user);
        if (!idempotency.lockClaim(reservation.command())) {
            throw IdempotencyConflictException.claimLost();
        }
        var conversation = conversations.findOwnedForUpdate(input.conversationId(), user.userId(), user.companyId())
                .orElseThrow(AssistantNotFoundException::new);
        if (!reservation.token().equals(conversation.getActiveGenerationToken())) {
            throw AssistantException.busy();
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var metadata = new GenerationMetadata(1, input.schemaVersion(), "1", generation.metadata(),
                context.application() == null ? null : context.application().applicationId(), context.selectedProductCode(), context.currentFieldCode());
        var question = messages.saveAndFlush(AssistantMessage.question(conversation.getId(), conversation.getNextSequence(), input.message(), now));
        var answer = messages.saveAndFlush(AssistantMessage.answer(question, generation.reply().answer(),
                generation.reply().recommendedProductCode(), metadata, now));
        var body = new MessageExchange(conversation.getId(), view(question), view(answer));
        if (conversations.complete(conversation.getId(), user.userId(), user.companyId(), reservation.token(), now) != 1) {
            throw AssistantException.busy();
        }
        return idempotency.complete(reservation.command(), 200, body, jsonHeaders());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(AssistantReservation.Generation reservation) {
        idempotency.markRetryableFailure(reservation.command());
        conversations.release(reservation.input().conversationId(), reservation.owner().userId(), reservation.owner().companyId(), reservation.token());
    }

    private void checkConversation(UUID id, CurrentUser user) {
        if (!conversations.existsByIdAndUserIdAndCompanyId(id, user.userId(), user.companyId())) {
            throw new AssistantNotFoundException();
        }
    }

    private ChatMessage view(AssistantMessage message) {
        return new ChatMessage(message.getId(), message.getSequence(), message.getRole(), message.getText(),
                message.getRecommendedProductCode(), message.getReplyToMessageId(), message.getCreatedAt());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
