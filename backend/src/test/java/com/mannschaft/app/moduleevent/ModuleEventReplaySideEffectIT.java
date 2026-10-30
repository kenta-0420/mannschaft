package com.mannschaft.app.moduleevent;

import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.quickmemo.dto.UpdateSettingsRequest;
import com.mannschaft.app.quickmemo.entity.UserQuickMemoSettingsEntity;
import com.mannschaft.app.quickmemo.repository.UserQuickMemoSettingsRepository;
import com.mannschaft.app.quickmemo.service.UserQuickMemoSettingsService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M15: 公式再配送と実設定サービスの短TXを結ぶ、業務DB冪等性の検体。
 * 検体listener自身の冪等化を検証し、C/F等の全実業務購読への接続を証明しない。
 * 設定TXと非同期監査TXは別々にcommitするため、両方を実DBで観測する。
 */
@ModuleEventMySqlTest
@Import(ModuleEventReplaySideEffectIT.Fixture.class)
@TestPropertySource(properties = "spring.modulith.moments.enabled=false")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventReplaySideEffectIT extends AbstractMySqlIntegrationTest {
    private static final String LISTENER_ID = "module-replay-side-effect-it";
    private static final String SETTINGS_EVENT = "QUICK_MEMO_SETTINGS_UPDATED";
    private static final String WITHDRAWAL_EVENT = "WITHDRAWAL_REQUESTED";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Instant AGED_PUBLICATION = Instant.parse("2020-01-01T00:00:00Z");
    @Autowired private UserRepository users;
    @Autowired private UserService userService;
    @Autowired private UserQuickMemoSettingsRepository settings;
    @Autowired private EventPublicationRepository publications;
    @Autowired private IncompleteEventPublications replay;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Listeners listeners;
    @Autowired @Qualifier("module-event-pool") private Executor modulePool;
    @Autowired @Qualifier("purge-pool") private Executor purgePool;
    @Autowired @Qualifier("event-pool") private Executor eventPool;
    @Autowired @Qualifier("withdrawal-pool") private Executor withdrawalPool;
    @PersistenceContext private EntityManager em;
    private final List<Long> owners = new ArrayList<>();
    private final List<UUID> publicationIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<OwnedAudit> auditIds = new ArrayList<>();

    @Test
    void M15_書込commit後の配送失敗を再配信しても設定と監査を二重更新しない() {
        prepare();
        Long owner = createUser();
        Probe probe = seed(owner, false, true);
        resubmit();
        await().atMost(WAIT).untilAsserted(() -> assertThat(probe.written).hasValue(1));
        idle();
        assertPendingFailure();
        assertThat(probe.invocations).hasValue(1);
        assertThat(probe.skipped).hasValue(0);
        Map<String, Object> committedSettings = settingsRow(owner);
        assertThat(committedSettings.get("reminder_enabled")).isEqualTo(true);
        List<Map<String, Object>> committedAudit = auditRows(owner, SETTINGS_EVENT);
        assertThat(committedAudit).hasSize(1);
        assertThat(committedAudit.getFirst().get("metadata").toString()).contains("NEW_ONLY");

        resubmit();
        await().atMost(WAIT).untilAsserted(() -> assertThat(probe.invocations).hasValue(2));
        idle();
        assertThat(probe.written).hasValue(1);
        assertThat(probe.skipped).hasValue(1);
        // user_id・設定値・created_at・updated_atを含む全列が不変。
        assertThat(settingsRow(owner)).isEqualTo(committedSettings);
        // 監査ID・owner・時刻・metadataを含む全列が不変で、行追加もない。
        assertThat(auditRows(owner, SETTINGS_EVENT)).isEqualTo(committedAudit);
        assertCompleted();
    }

    @Test
    void M15_初回書込前失敗と再配送の間に退会した本人へ書き込まない() {
        prepare();
        Long owner = createUser();
        Probe probe = seed(owner, true, false);
        Map<String, Object> originalSettings = settingsRow(owner);
        resubmit();
        await().atMost(WAIT).untilAsserted(() -> assertThat(probe.invocations).hasValue(1));
        idle();
        assertPendingFailure();
        assertThat(probe.written).hasValue(0);
        assertThat(auditRows(owner, SETTINGS_EVENT)).isEmpty();
        userService.requestWithdrawal(owner, new RequestWithdrawalRequest(null));
        idle();
        assertThat(auditRows(owner, WITHDRAWAL_EVENT)).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ? AND deleted_at IS NOT NULL", owner)).isEqualTo(1);
        assertThat(userService.getActiveDisplayStates(List.of(owner))).doesNotContainKey(owner);

        resubmit();
        await().atMost(WAIT).untilAsserted(() -> assertThat(probe.skipped).hasValue(1));
        idle();
        assertThat(probe.invocations).hasValue(2);
        assertThat(probe.written).hasValue(0);
        assertThat(settingsRow(owner)).isEqualTo(originalSettings);
        assertThat(settingsRow(owner).get("reminder_enabled")).isEqualTo(false);
        assertThat(auditRows(owner, SETTINGS_EVENT)).isEmpty();
        assertCompleted();
    }

    private void prepare() {
        idle();
        // 公式APIは全対象を回収するため、他者のpublicationが存在する環境では実行しない。
        assertThat(count("SELECT COUNT(*) FROM event_publication")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_publication_attempts")).isZero();
    }

    private Long createUser() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var user = users.saveAndFlush(UserEntity.builder()
                    .email("m15-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName("再配信").displayName("再配信検体")
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build());
            // 途中失敗でも自己作成IDを保持して回収する。
            owners.add(user.getId());
            settings.saveAndFlush(UserQuickMemoSettingsEntity.builder()
                    .userId(user.getId()).reminderEnabled(false).build());
            return user.getId();
        });
    }

    private Probe seed(Long owner, boolean before, boolean after) {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        Probe probe = listeners.register(eventId, before, after);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var publication = publications.create(TargetEventPublication.of(
                    new ReplaySideEffectEvent(eventId, owner, LocalDateTime.now()),
                    PublicationTargetIdentifier.of(LISTENER_ID), AGED_PUBLICATION));
            publicationIds.add(publication.getIdentifier());
            new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, em)
                    .saveAndFlush(EventPublicationAttemptEntity.builder()
                            .publicationId(publication.getIdentifier()).retryCount(0)
                            .status(ModuleEventAttemptStatus.PENDING).claimToken(UUID.randomUUID())
                            .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        });
        return probe;
    }

    private void resubmit() {
        replay.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
    }

    private void assertPendingFailure() {
        for (UUID id : publicationIds) {
            assertThat(count("SELECT COUNT(*) FROM event_publication WHERE id = ? AND completion_date IS NULL", binary(id)))
                    .isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM event_publication_attempts WHERE publication_id = ? "
                    + "AND status = 'PENDING' AND retry_count = 1 AND failure_code = 'LISTENER_FAILURE' "
                    + "AND lease_until IS NULL", binary(id))).isEqualTo(1);
        }
    }

    private void assertCompleted() {
        for (UUID id : publicationIds) {
            assertThat(count("SELECT COUNT(*) FROM event_publication WHERE id = ?", binary(id))).isZero();
            assertThat(count("SELECT COUNT(*) FROM event_publication_attempts WHERE publication_id = ?", binary(id))).isZero();
        }
    }

    private Map<String, Object> settingsRow(Long owner) {
        return jdbc.queryForMap("SELECT * FROM user_quick_memo_settings WHERE user_id = ?", owner);
    }

    private List<Map<String, Object>> auditRows(Long owner, String type) {
        var rows = jdbc.queryForList("SELECT * FROM audit_logs WHERE user_id = ? AND event_type = ? ORDER BY id", owner, type);
        for (var row : rows) auditIds.add(new OwnedAudit(((Number) row.get("id")).longValue(), owner, type));
        return rows;
    }

    @AfterEach
    void 自己作成検体だけを回収する() {
        idle();
        // 途中assert失敗時も、自己生成owner・event種別で監査IDを確定する。
        for (Long owner : owners) {
            auditRows(owner, SETTINGS_EVENT);
            auditRows(owner, WITHDRAWAL_EVENT);
        }
        for (OwnedAudit audit : auditIds.stream().distinct().toList()) {
            jdbc.update("DELETE FROM audit_logs WHERE id = ? AND user_id = ? AND event_type = ?",
                    audit.id(), audit.owner(), audit.type());
        }
        // 使い捨て試験データの後始末であり、本番監査のprivacy/retention仕様ではない。
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var attempts = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, em);
            for (UUID id : publicationIds) {
                attempts.deleteAll(em.createQuery("SELECT a FROM EventPublicationAttemptEntity a WHERE a.publicationId = :id",
                        EventPublicationAttemptEntity.class).setParameter("id", id).getResultList());
            }
            em.flush();
            publications.deletePublications(publicationIds);
            for (Long owner : owners) {
                settings.deleteById(owner);
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", owner).executeUpdate();
            }
        });
        eventIds.forEach(listeners::remove);
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    private void idle() {
        // moduleから監査/退会taskが派生し得るため、派生元を先にdrainする。
        for (Executor executor : List.of(modulePool, purgePool, withdrawalPool, eventPool)) {
            var pool = ((ThreadPoolTaskExecutor) executor).getThreadPoolExecutor();
            await().atMost(WAIT).until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty()
                    && pool.getCompletedTaskCount() == pool.getTaskCount());
        }
    }

    private static byte[] binary(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** 回収時にもownerと種別を照合する自己監査ID。 */
    private record OwnedAudit(Long id, Long owner, String type) { }

    /** 他の試験contextへ自動混入しない明示import専用検体構成。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        Listeners replaySideEffectListeners(UserService users, UserQuickMemoSettingsService settings, JdbcTemplate jdbc) {
            return new Listeners(users, settings, jdbc);
        }
    }

    /** Jacksonが公式publicationから復元する検体イベント。 */
    public record ReplaySideEffectEvent(UUID id, Long userId, LocalDateTime occurredAt) implements ReliableEvent { }

    /** 自己登録したイベントにだけ失敗を注入する観測器。 */
    static final class Probe {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicInteger written = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicBoolean failBeforeOnce;
        final AtomicBoolean failAfterOnce;
        Probe(boolean before, boolean after) {
            failBeforeOnce = new AtomicBoolean(before);
            failAfterOnce = new AtomicBoolean(after);
        }
    }

    /** 冪等化と現在状態判定の責務を持つ検体購読。外側TXは付けない。 */
    public static class Listeners {
        private final UserService users;
        private final UserQuickMemoSettingsService settings;
        private final JdbcTemplate jdbc;
        private final ConcurrentMap<UUID, Probe> probes = new ConcurrentHashMap<>();
        Listeners(UserService users, UserQuickMemoSettingsService settings, JdbcTemplate jdbc) {
            this.users = users;
            this.settings = settings;
            this.jdbc = jdbc;
        }
        Probe register(UUID id, boolean before, boolean after) {
            Probe probe = new Probe(before, after);
            if (probes.putIfAbsent(id, probe) != null) throw new IllegalArgumentException("検体ID重複");
            return probe;
        }
        void remove(UUID id) { probes.remove(id); }

        @ModuleEventListener(id = LISTENER_ID)
        public void receive(ReplaySideEffectEvent event) {
            Probe probe = probes.get(event.id());
            if (probe == null) throw new IllegalStateException("未登録の検体");
            probe.invocations.incrementAndGet();
            if (probe.failBeforeOnce.compareAndSet(true, false)) throw new IllegalStateException("書込前の意図的失敗");
            var state = users.getActiveDisplayStates(List.of(event.userId())).get(event.userId());
            if (state == null || !state.active() || Boolean.TRUE.equals(settings.getSettings(event.userId()).reminderEnabled())) {
                probe.skipped.incrementAndGet();
                return;
            }
            settings.updateSettings(event.userId(),
                    new UpdateSettingsRequest(true, null, null, null, null, null, null), "NEW_ONLY");
            probe.written.incrementAndGet();
            // 設定TX復帰後、別の非同期監査TXのcommitも実DBで観測してから配送だけ失敗させる。
            if (probe.failAfterOnce.compareAndSet(true, false)) {
                await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND event_type = ?",
                        Long.class, event.userId(), SETTINGS_EVENT)).isEqualTo(1L));
                throw new IllegalStateException("commit後の意図的失敗");
            }
        }
    }
}
