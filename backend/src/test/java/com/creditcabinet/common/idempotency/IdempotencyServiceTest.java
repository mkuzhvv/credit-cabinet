package com.creditcabinet.common.idempotency;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "idempotency.db-test", matches = "true")
@Timeout(15)
class IdempotencyServiceTest {

    private static final UUID USER = UUID.fromString("b827db91-df42-4d75-b5e0-8f0a7a512ee1");
    private static final UUID COMPANY = UUID.fromString("97daa92f-127c-4f70-8a0c-0711f13c62ef");
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static AnnotationConfigApplicationContext context;
    private static IdempotencyService service;
    private static JdbcTemplate jdbc;
    private static JsonMapper mapper;
    private static TransactionTemplate transaction;
    private final String operation = "test.stage05." + UUID.randomUUID();

    @BeforeAll
    static void connect() {
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> new DriverManagerDataSource(
                System.getProperty("test.db.url", "jdbc:postgresql://127.0.0.1:5432/credit_cabinet"),
                System.getProperty("test.db.username", "credit_cabinet"), System.getProperty("test.db.password", "")));
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
        context.registerBean(PlatformTransactionManager.class, () -> new JdbcTransactionManager(context.getBean(DataSource.class)));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.register(RequestHasher.class, IdempotencyRepository.class, IdempotencyService.class);
        context.refresh();
        service = context.getBean(IdempotencyService.class);
        jdbc = context.getBean(JdbcTemplate.class);
        mapper = context.getBean(JsonMapper.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @AfterEach
    void removeOwnRecords() {
        jdbc.update("DELETE FROM idempotency_record WHERE operation = ?", operation);
    }

    @Test
    void canonicalHashIgnoresObjectOrderAndNumberScaleButPreservesValues() {
        RequestHasher hasher = context.getBean(RequestHasher.class);
        assertThat(hasher.hash(mapper.readTree("{\"b\":{\"y\":1.0,\"x\":null},\"a\":[1,2]}")))
                .isEqualTo(hasher.hash(mapper.readTree("{ \"a\": [1,2], \"b\": {\"x\": null, \"y\":1} }")));
        assertThat(hasher.hash(List.of(1, 2))).isNotEqualTo(hasher.hash(List.of(2, 1)));
        assertThat(hasher.hash(Map.of("amount", "1.00"))).isNotEqualTo(hasher.hash(Map.of("amount", "2.00")));
    }

    @Test
    void concurrentRequestsHaveOneOwnerAndChangedBodyConflicts() throws Exception {
        IdempotencyScope scope = scope();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var call = (java.util.concurrent.Callable<Object>) () -> {
                start.await();
                try {
                    return service.begin(scope, Map.of("productCode", "OVERDRAFT"), LEASE);
                } catch (IdempotencyConflictException ex) {
                    return ex.getCode();
                }
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            List<Object> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(IdempotencyResult.Claim.class::isInstance).count()).isEqualTo(1);
            assertThat(results).contains("OPERATION_IN_PROGRESS");
        }
        assertThatThrownBy(() -> service.begin(scope, Map.of("productCode", "LONG_TERM"), LEASE))
                .isInstanceOfSatisfying(IdempotencyConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void replaysStoredResponseForTheSameUserScopeWithoutTransportHeaders() {
        IdempotencyScope scope = scope();
        var claim = claim(scope);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(java.net.URI.create("/api/v1/applications/123"));
        headers.set(HttpHeaders.SET_COOKIE, "JSESSIONID=must-not-be-stored");
        headers.set("X-CSRF-TOKEN", "must-not-be-stored");
        headers.set("X-Request-Id", "old-request");
        transaction.executeWithoutResult(tx -> service.complete(claim, 201, Map.of("id", "123"), headers));
        var sameScope = new IdempotencyScope(USER, COMPANY, operation, "collection", scope.key());
        var replay = (IdempotencyResult.Replay) service.begin(sameScope, Map.of("productCode", "OVERDRAFT"), LEASE);
        assertThat(replay.response().status()).isEqualTo(201);
        assertThat(replay.response().body()).isEqualTo(mapper.readTree("{\"id\":\"123\"}"));
        assertThat(replay.response().headers()).containsExactlyEntriesOf(Map.of("Location", "/api/v1/applications/123"));
        assertThat(replay.response().toResponseEntity().getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(service.begin(new IdempotencyScope(USER, COMPANY, operation, "other-resource", scope.key()),
                Map.of("productCode", "OVERDRAFT"), LEASE)).isInstanceOf(IdempotencyResult.Claim.class);
    }

    @Test
    void successRequiresBusinessTransactionAndRollsBackWithIt() {
        IdempotencyScope scope = scope();
        transaction.executeWithoutResult(tx -> {
            service.complete(claim(scope), 201, Map.of("id", "rolled-back"), HttpHeaders.EMPTY);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE operation = ?", Long.class, operation)).isZero();
        var claim = claim(scope);
        assertThatThrownBy(() -> service.complete(claim, 201, Map.of("id", "123"), HttpHeaders.EMPTY))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void failureAllowsRetryAndOldTokenCannotCompleteOrReleaseNewOwner() {
        IdempotencyScope scope = scope();
        var old = claim(scope);
        assertThat(service.markRetryableFailure(old)).isTrue();
        var current = claim(scope);
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(service.markRetryableFailure(old)).isFalse();
        assertThatThrownBy(() -> complete(old)).isInstanceOf(IdempotencyConflictException.class);
        complete(current);
    }

    @Test
    void expiredLeaseRejectsItsOwnerAndCanBeClaimedAgain() {
        IdempotencyScope scope = scope();
        var old = claim(scope);
        expireLease(old);
        assertThatThrownBy(() -> complete(old)).isInstanceOf(IdempotencyConflictException.class);
        assertThat(service.markRetryableFailure(old)).isFalse();
        var current = claim(scope);
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThatThrownBy(() -> complete(old)).isInstanceOf(IdempotencyConflictException.class);
        complete(current);
    }

    @Test
    void cleanupRemovesExpiredResultsAndKeepsRecentSuccessAndUnfinishedCommands() {
        transaction.executeWithoutResult(tx -> {
            var expired = claim(scope());
            complete(expired);
            jdbc.update("""
                    UPDATE idempotency_record SET updated_at = statement_timestamp() - interval '25 hours',
                        expires_at = statement_timestamp() - interval '1 hour' WHERE id = ?
                    """, expired.recordId());
            var failed = claim(scope());
            service.markRetryableFailure(failed);
            jdbc.update("UPDATE idempotency_record SET expires_at = statement_timestamp() - interval '1 hour' WHERE id = ?", failed.recordId());
            var recent = claim(scope());
            complete(recent);
            var unfinished = claim(scope());
            expireLease(unfinished);
            service.deleteExpired();
            List<UUID> remaining = jdbc.queryForList("SELECT id FROM idempotency_record WHERE operation = ?", UUID.class, operation);
            assertThat(remaining).containsExactlyInAnyOrder(recent.recordId(), unfinished.recordId());
            tx.setRollbackOnly();
        });
    }

    private IdempotencyScope scope() {
        return new IdempotencyScope(USER, COMPANY, operation, "collection", UUID.randomUUID());
    }

    private IdempotencyResult.Claim claim(IdempotencyScope scope) {
        return (IdempotencyResult.Claim) service.begin(scope, Map.of("productCode", "OVERDRAFT"), LEASE);
    }

    private void complete(IdempotencyResult.Claim claim) {
        transaction.executeWithoutResult(tx -> service.complete(claim, 201, Map.of("id", "123"), HttpHeaders.EMPTY));
    }

    private void expireLease(IdempotencyResult.Claim claim) {
        jdbc.update("UPDATE idempotency_record SET locked_until = clock_timestamp() - interval '1 second' WHERE id = ?", claim.recordId());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {
    }
}
