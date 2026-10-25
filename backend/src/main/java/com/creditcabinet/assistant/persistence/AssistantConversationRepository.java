package com.creditcabinet.assistant.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.creditcabinet.assistant.model.AssistantConversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, UUID> {

    boolean existsByIdAndUserIdAndCompanyId(UUID id, UUID userId, UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AssistantConversation c WHERE c.id = :id AND c.userId = :userId AND c.companyId = :companyId")
    Optional<AssistantConversation> findOwnedForUpdate(UUID id, UUID userId, UUID companyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE assistant_conversation
            SET active_generation_token = :token,
                generation_locked_until = clock_timestamp() + :leaseMillis * interval '1 millisecond'
            WHERE id = :id AND user_id = :userId AND company_id = :companyId
                AND (active_generation_token IS NULL OR generation_locked_until <= clock_timestamp())
            """, nativeQuery = true)
    int acquire(UUID id, UUID userId, UUID companyId, UUID token, long leaseMillis);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE assistant_conversation
            SET active_generation_token = NULL, generation_locked_until = NULL,
                next_sequence = next_sequence + 2, updated_at = :now
            WHERE id = :id AND user_id = :userId AND company_id = :companyId
                AND active_generation_token = :token AND generation_locked_until > clock_timestamp()
            """, nativeQuery = true)
    int complete(UUID id, UUID userId, UUID companyId, UUID token, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE assistant_conversation SET active_generation_token = NULL, generation_locked_until = NULL
            WHERE id = :id AND user_id = :userId AND company_id = :companyId AND active_generation_token = :token
            """, nativeQuery = true)
    int release(UUID id, UUID userId, UUID companyId, UUID token);
}
