package com.creditcabinet.assistant.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "assistant_message")
public class AssistantMessage {

    @Id
    private UUID id;
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;
    @Column(nullable = false)
    private long sequence;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;
    @Column(nullable = false, length = 3000)
    private String text;
    @Column(name = "recommended_product_code", length = 64)
    private String recommendedProductCode;
    @Column(name = "reply_to_message_id")
    private UUID replyToMessageId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generation_metadata", columnDefinition = "jsonb")
    private GenerationMetadata generationMetadata;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AssistantMessage() {
    }

    public static AssistantMessage question(UUID conversationId, long sequence, String text, Instant now) {
        AssistantMessage message = new AssistantMessage();
        message.id = UUID.randomUUID();
        message.conversationId = conversationId;
        message.sequence = sequence;
        message.role = Role.USER;
        message.text = text;
        message.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        return message;
    }

    public static AssistantMessage answer(AssistantMessage question, String text, String productCode,
            GenerationMetadata metadata, Instant now) {
        AssistantMessage message = question(question.conversationId, question.sequence + 1, text, now);
        message.role = Role.ASSISTANT;
        message.recommendedProductCode = productCode;
        message.replyToMessageId = question.id;
        message.generationMetadata = metadata;
        return message;
    }

    public UUID getId() {
        return id;
    }
    public long getSequence() {
        return sequence;
    }
    public Role getRole() {
        return role;
    }
    public String getText() {
        return text;
    }
    public String getRecommendedProductCode() {
        return recommendedProductCode;
    }
    public UUID getReplyToMessageId() {
        return replyToMessageId;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }

    public enum Role {
        USER, ASSISTANT
    }
}
