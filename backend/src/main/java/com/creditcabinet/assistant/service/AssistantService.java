package com.creditcabinet.assistant.service;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.creditcabinet.assistant.api.SendMessageRequest;
import com.creditcabinet.assistant.integration.AssistantGateway;
import com.creditcabinet.assistant.integration.AssistantGeneration;
import com.creditcabinet.common.idempotency.StoredResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantService {

    private final AssistantStateService state;
    private final AssistantGateway gateway;
    private final AssistantReplyValidator replies;
    private final AssistantProperties properties;
    private final ExecutorService executor;

    public AssistantService(AssistantStateService state, AssistantGateway gateway, AssistantReplyValidator replies,
            AssistantProperties properties, @Qualifier("assistantExecutor") ExecutorService executor) {
        this.state = state;
        this.gateway = gateway;
        this.replies = replies;
        this.properties = properties;
        this.executor = executor;
    }

    @Transactional(propagation = Propagation.NEVER)
    public StoredResponse send(UUID conversationId, SendMessageRequest request, UUID key, UUID requestId) {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        var prepared = state.prepare(conversationId, request, key);
        if (prepared instanceof AssistantReservation.Replay replay) {
            return replay.response();
        }
        var reservation = (AssistantReservation.Generation) prepared;
        boolean completed = false;
        try {
            var call = executor.submit(() -> gateway.generate(reservation.input(), requestId));
            var result = await(call, deadline);
            replies.validate(reservation.input(), result);
            if (System.nanoTime() >= deadline) {
                throw AssistantException.timeout(null);
            }
            StoredResponse response = state.finish(reservation, result);
            completed = true;
            return response;
        } finally {
            if (!completed) {
                state.fail(reservation);
            }
        }
    }

    private AssistantGeneration await(Future<AssistantGeneration> call, long deadline) {
        try {
            return call.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            call.cancel(true);
            throw AssistantException.timeout(ex);
        } catch (InterruptedException ex) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw AssistantException.unavailable(ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof AssistantException failure) {
                throw failure;
            }
            throw AssistantException.unavailable(ex.getCause());
        }
    }
}
