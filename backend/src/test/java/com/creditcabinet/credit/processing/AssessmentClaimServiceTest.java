package com.creditcabinet.credit.processing;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.creditcabinet.credit.persistence.AssessmentClaimRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(AssessmentClaimServiceTest.Database.class)
@TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
@EnabledIfSystemProperty(named = "applications.db-test", matches = "true")
@Timeout(15)
class AssessmentClaimServiceTest {

    private static final String SCHEMA = "stage11_claim_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private AssessmentClaimService service;
    @Autowired private AssessmentClaimRepository attempts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @AfterEach
    void clearQueue() {
        jdbc.update("DELETE FROM credit_assessment");
    }

    @AfterAll
    static void dropTestSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void claimsInOrderAndSkipsFutureAndRunningAttempts() {
        UUID future = insert(120);
        UUID first = insert(-60);
        UUID second = insert(-30);
        UUID running = insert(-120);
        jdbc.update("""
                UPDATE credit_assessment SET processing_status = 'RUNNING', execution_count = 3,
                    lock_token = ?, locked_until = clock_timestamp() + interval '30 seconds'
                WHERE id = ?
                """, UUID.randomUUID(), running);

        AssessmentClaim claim = service.claimNext().orElseThrow();
        assertThat(claim.assessmentId()).isEqualTo(first);
        assertThat(claim.executionCount()).isEqualTo(1);
        assertThat(claim.token()).isNotNull();
        assertThat(claim.applicationId()).isEqualTo(jdbc.queryForObject("SELECT application_id FROM credit_assessment WHERE id = ?", UUID.class, first));
        assertThat(jdbc.queryForObject("SELECT locked_until > clock_timestamp() AND processing_status = 'RUNNING' FROM credit_assessment WHERE id = ?", Boolean.class, first)).isTrue();
        assertThat(service.claimNext().orElseThrow().assessmentId()).isEqualTo(second);
        assertThat(service.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT execution_count FROM credit_assessment WHERE id = ?", Integer.class, future)).isZero();
        assertThat(jdbc.queryForObject("SELECT execution_count FROM credit_assessment WHERE id = ?", Integer.class, running)).isEqualTo(3);
    }

    @Test
    void skipsLockedAttemptAndCommitsBeforeReturningToCaller() throws Exception {
        UUID first = insert(-60);
        UUID second = insert(-30);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holding = executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                AssessmentClaim claim = attempts.claimNext(Duration.ofSeconds(30)).orElseThrow();
                locked.countDown();
                await(release);
                return claim;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var taking = executor.submit(service::claimNext);
                AssessmentClaim other = taking.get(5, TimeUnit.SECONDS).orElseThrow();
                assertThat(other.assessmentId()).isEqualTo(second);
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                    jdbc.update("UPDATE credit_assessment SET execution_count = execution_count WHERE id = ?", second);
                });
            } finally {
                release.countDown();
            }
            assertThat(holding.get(5, TimeUnit.SECONDS).assessmentId()).isEqualTo(first);
        }
    }

    @Test
    void concurrentWorkersCannotClaimTheSameAttempt() throws Exception {
        UUID id = insert(-30);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> claimAfter(start));
            var second = executor.submit(() -> claimAfter(start));
            start.countDown();
            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            assertThat(a.isPresent() ^ b.isPresent()).isTrue();
            assertThat(a.or(() -> b).orElseThrow().assessmentId()).isEqualTo(id);
        }
        assertThat(jdbc.queryForObject("SELECT execution_count FROM credit_assessment WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void ownedLockRejectsWrongTokenChangedOwnerAndExpiredLease() {
        insert(-30);
        AssessmentClaim claim = service.claimNext().orElseThrow();
        assertThatThrownBy(() -> attempts.lockOwned(claim)).isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var wrong = new AssessmentClaim(claim.assessmentId(), claim.applicationId(), UUID.randomUUID(), claim.lockedUntil(), claim.executionCount());
            assertThat(attempts.lockOwned(wrong)).isEmpty();
            assertThat(attempts.lockOwned(claim)).contains(claim);
        });
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE credit_assessment SET lock_token = ? WHERE id = ?", token, claim.assessmentId());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThat(attempts.lockOwned(claim)).isEmpty());
        var changed = new AssessmentClaim(claim.assessmentId(), claim.applicationId(), token, claim.lockedUntil(), claim.executionCount());
        jdbc.update("UPDATE credit_assessment SET locked_until = clock_timestamp() - interval '1 millisecond' WHERE id = ?", claim.assessmentId());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThat(attempts.lockOwned(changed)).isEmpty());
        assertThat(service.claimNext()).isEmpty();
    }

    @Test
    void rollbackRestoresReadyStatusAndExecutionCount() {
        UUID id = insert(-30);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            attempts.claimNext(Duration.ofSeconds(30)).orElseThrow();
            throw new IllegalStateException("Rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT processing_status FROM credit_assessment WHERE id = ?", String.class, id)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT lock_token IS NULL AND locked_until IS NULL AND execution_count = 0 FROM credit_assessment WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(service.claimNext().orElseThrow().executionCount()).isEqualTo(1);
    }

    private Optional<AssessmentClaim> claimAfter(CountDownLatch start) {
        await(start);
        return service.claimNext();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private UUID insert(int delaySeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO credit_assessment
                    (id, application_id, attempt_no, processing_status, snapshot_schema_version, input_snapshot,
                     policy_version, feature_schema_version, expected_model_version, risk_cutoff, next_run_at)
                VALUES (?, ?, 1, 'READY', 2, '{}'::jsonb, 'test-policy', '4', 'test-rules', 0.50,
                    clock_timestamp() + ? * interval '1 second')
                """, id, UUID.randomUUID(), delaySeconds);
        return id;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Database {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:5432/credit_cabinet?currentSchema=" + SCHEMA,
                    "credit_cabinet", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE SCHEMA " + SCHEMA);
            jdbc.execute("CREATE TABLE credit_assessment (LIKE public.credit_assessment INCLUDING ALL)");
            return jdbc;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        AssessmentClaimRepository repository(JdbcTemplate jdbc) {
            return new AssessmentClaimRepository(jdbc);
        }

        @Bean
        AssessmentClaimService service(AssessmentClaimRepository repository) {
            return new AssessmentClaimService(repository, new ProcessingProperties(Duration.ofSeconds(30), Duration.ofSeconds(1),
                    Duration.ofSeconds(5), Duration.ofSeconds(1), 3));
        }
    }
}
