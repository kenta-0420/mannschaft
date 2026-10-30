package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M04/M07/M08/M10/M11: 実 MySQL の保存行から、公式の指定先再配信入口を通す。
 * 初期データも公式 Repository と Spring Data Repository で作り、手書き INSERT は使わない。
 * cutoff ちょうど・空き容量・実 ShedLock は別の試練が必要で、このクラスで代替しない。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventReplayIT extends AbstractMySqlIntegrationTest {
    private static final Duration CUTOFF = Duration.ofMinutes(5);
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final int REPLAY_LIMIT = 100;
    @PersistenceContext private EntityManager entityManager;
    @Autowired private EventPublicationRepository publicationRepository;
    @Autowired private IncompleteEventPublications replay;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModuleEventReplayTestFixture.Listeners listeners;
    @Autowired @Qualifier("module-event-pool") private Executor executor;
    private final List<UUID> publications = new ArrayList<>();
    private final List<UUID> attempts = new ArrayList<>();
    private UUID target;
    private ModuleEventReplayTestFixture.Probe probe;

    @BeforeEach
    void 自分の検体を登録する() {
        idle();
        // 前の試練が残した行を黙って削除せず、入力前提の不成立として検知する。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts", Long.class)).isZero();
        target = UUID.randomUUID();
        probe = listeners.register(target);
    }

    @AfterEach
    void 自分の行だけを片付ける() {
        idle();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager)
                        .deleteAllById(attempts);
                entityManager.flush();
                publicationRepository.deletePublications(publications);
            });
        } finally {
            if (target != null) listeners.remove(target);
        }
    }

    @Test
    void M07_空の再配信は購読を呼ばない() {
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        idle();
        assertThat(probe.invocations).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts", Long.class)).isZero();
    }

    @Test
    void M04_保存済み一件を正常再配信すると同じ本表と補助行が消える() {
        seed(1, 0, ModuleEventAttemptStatus.PENDING, null, Instant.now().minus(Duration.ofDays(1)));
        probe.fail = false;
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        finished(1);
        assertThat(mainCount()).isZero();
        assertThat(attemptRows()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 100, 101})
    void M08_一回の再配信は百件までで受理分だけ試行を数える(int count) {
        seed(count, 0, ModuleEventAttemptStatus.PENDING, null, Instant.now().minus(Duration.ofDays(1)));
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        finished(Math.min(count, REPLAY_LIMIT));
        assertThat(probe.invocations).hasValue(Math.min(count, REPLAY_LIMIT));
        assertThat(attemptRows().stream().filter(row -> ((Number) row.get("retry_count")).intValue() == 1))
                .hasSize(Math.min(count, REPLAY_LIMIT));
        assertThat(mainCount()).isEqualTo(count);
    }

    @Test
    void M08_cutoffより新しい行は配信しない() {
        seed(1, 0, ModuleEventAttemptStatus.PENDING, null, Instant.now().plus(Duration.ofDays(1)));
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        idle();
        assertThat(probe.invocations).hasValue(0);
        assertThat(mainCount()).isEqualTo(1L);
    }

    @Test
    void M10_有効なleaseは再配信しない() {
        seed(1, 0, ModuleEventAttemptStatus.IN_PROGRESS, instantNow().plus(Duration.ofDays(1)),
                Instant.now().minus(Duration.ofDays(1)));
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        idle();
        assertThat(probe.invocations).hasValue(0);
        assertThat(attemptRows().getFirst().get("retry_count")).isEqualTo(0);
    }

    @Test
    void M10_期限切れleaseは新しい世代で一度だけ再配信する() {
        UUID oldToken = seed(1, 0, ModuleEventAttemptStatus.IN_PROGRESS, instantNow().minus(Duration.ofDays(1)),
                Instant.now().minus(Duration.ofDays(1)));
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        finished(1);
        var row = attemptRows().getFirst();
        assertThat(row.get("retry_count")).isEqualTo(1);
        assertThat(row.get("claim_token")).isNotEqualTo(binary(oldToken));
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    @Test
    void M11_九回済みの失敗は十回で隔離し次回は投入しない() {
        seed(1, 9, ModuleEventAttemptStatus.PENDING, null, Instant.now().minus(Duration.ofDays(1)));
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        finished(1);
        var row = attemptRows().getFirst();
        assertThat(row.get("retry_count")).isEqualTo(10);
        assertThat(row.get("status")).isEqualTo("QUARANTINED");
        assertThat(row.get("quarantined_at")).isNotNull();
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        idle();
        assertThat(probe.invocations).hasValue(1);
        assertThat(mainCount()).isEqualTo(1L);
    }

    @Test
    void M11_保存済み隔離行は十一回目を投入しない() {
        seed(1, 10, ModuleEventAttemptStatus.QUARANTINED, null, Instant.now().minus(Duration.ofDays(1)));
        replay.resubmitIncompletePublicationsOlderThan(CUTOFF);
        idle();
        assertThat(probe.invocations).hasValue(0);
        assertThat(attemptRows().getFirst().get("retry_count")).isEqualTo(10);
    }

    private UUID seed(int count, int retries, ModuleEventAttemptStatus state,
            Instant lease, Instant publishedAt) {
        UUID token = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var repository = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager);
            for (int index = 0; index < count; index++) {
                var event = new ModuleEventReplayTestFixture.ReplayEvent(target, 7L, LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE));
                TargetEventPublication publication = publicationRepository.create(TargetEventPublication.of(event,
                        PublicationTargetIdentifier.of(ModuleEventReplayTestFixture.LISTENER_ID),
                        publishedAt.plusNanos(index * 1000L)));
                publications.add(publication.getIdentifier());
                EventPublicationAttemptEntity attempt = repository.save(EventPublicationAttemptEntity.builder()
                        .publicationId(publication.getIdentifier()).retryCount(retries).status(state)
                        .claimToken(token).leaseUntil(lease).createdAt(instantNow()).updatedAt(instantNow())
                        .quarantinedAt(state == ModuleEventAttemptStatus.QUARANTINED ? instantNow() : null).build());
                attempts.add(attempt.getId());
            }
            entityManager.flush();
        });
        return token;
    }

    private List<java.util.Map<String, Object>> attemptRows() {
        return jdbc.queryForList("SELECT retry_count, status, claim_token, quarantined_at "
                + "FROM event_publication_attempts WHERE publication_id IN ("
                + String.join(",", publications.stream().map(id -> "?").toList()) + ")",
                publications.stream().map(ModuleEventReplayIT::binary).toArray());
    }

    private long mainCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE serialized_event LIKE ?",
                Long.class, "%" + target + "%");
    }

    private void finished(int expected) {
        await().atMost(WAIT).until(() -> probe.finished.get() >= expected);
        idle();
    }

    private void idle() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        var actual = pool.getThreadPoolExecutor();
        await().atMost(WAIT).until(() -> actual.getActiveCount() == 0 && actual.getQueue().isEmpty()
                && actual.getCompletedTaskCount() == actual.getTaskCount());
    }

    private static Instant instantNow() {
        return Instant.now();
    }

    private static byte[] binary(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
