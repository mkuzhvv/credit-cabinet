package com.creditcabinet.assistant.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assistant_conversation")
public class AssistantConversation {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;
    @Column(name = "active_generation_token")
    private UUID activeGenerationToken;
    @Column(name = "generation_locked_until")
    private Instant generationLockedUntil;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AssistantConversation() {
    }

    public AssistantConversation(UUID userId, UUID companyId, Instant now) {
        id = UUID.randomUUID();
        this.userId = userId;
        this.companyId = companyId;
        nextSequence = 1;
        createdAt = now.truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public long getNextSequence() {
        return nextSequence;
    }

    public UUID getActiveGenerationToken() {
        return activeGenerationToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
