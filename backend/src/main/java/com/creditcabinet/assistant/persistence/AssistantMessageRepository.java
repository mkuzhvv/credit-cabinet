package com.creditcabinet.assistant.persistence;

import java.util.List;
import java.util.UUID;

import com.creditcabinet.assistant.model.AssistantMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, UUID> {

    @Query("SELECT m FROM AssistantMessage m WHERE m.conversationId = :conversationId AND m.sequence > :after ORDER BY m.sequence")
    List<AssistantMessage> findAfter(UUID conversationId, long after, Pageable page);

    List<AssistantMessage> findTop20ByConversationIdOrderBySequenceDesc(UUID conversationId);
}
