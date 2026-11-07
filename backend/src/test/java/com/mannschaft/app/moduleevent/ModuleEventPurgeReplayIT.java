package com.mannschaft.app.moduleevent;

import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.util.SessionHashUtil;
import com.mannschaft.app.gdpr.service.AccountPurgeService;
import com.mannschaft.app.quickmemo.entity.UserQuickMemoSettingsEntity;
import com.mannschaft.app.quickmemo.repository.UserQuickMemoSettingsRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.redis.core.ValueOperations;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * M16: 実purge後の公式再配信で本人データが復活しないことを検証する検体。
 * 初回publisherとC/F実業務接続、Flyway由来の型/FKはこの試練の範囲外。
 */
@ModuleEventMySqlTest
@Import(ModuleEventPurgeReplayTestFixture.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventPurgeReplayIT extends AbstractMySqlIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    @Autowired private UserRepository users;
    @Autowired private UserService userService;
    @Autowired private UserQuickMemoSettingsRepository settings;
    @Autowired private AccountPurgeService purge;
    @Autowired private EventPublicationRepository publications;
    @Autowired private IncompleteEventPublications replay;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModuleEventPurgeReplayTestFixture.Listeners listeners;
    @Autowired @Qualifier("module-event-pool") private Executor modulePool;
    @Autowired @Qualifier("purge-pool") private Executor purgePool;
    @Autowired @Qualifier("event-pool") private Executor eventPool;
    @Autowired @Qualifier("withdrawal-pool") private Executor withdrawalPool;
    @PersistenceContext private EntityManager em;
    private final List<Long> owners = new ArrayList<>();
    private final List<UUID> publicationIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<Long> withdrawalAuditIds = new ArrayList<>();
    private final List<Long> settingsAuditIds = new ArrayList<>();
    private final List<Long> completionAuditIds = new ArrayList<>();
    private Long victim;
    private Long active;
    private String emailHash;
    private boolean auditBaselineConfirmed;

    @Test
    void M16_実purge後の公式再配信で復活せず別activeは実書込を完了する() {
        idle();
        assertThat(count("SELECT COUNT(*) FROM event_publication")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_publication_attempts")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE deleted_at < DATE_SUB(NOW(), INTERVAL 30 DAY)"))
                .as("公開purgeが他の利用者を処理しない前提").isZero();
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        victim = createUser("削除本人");
        active = createUser("保持対照");
        assertThat(auditIds("user_id = ? AND event_type = 'WITHDRAWAL_REQUESTED'", victim)).isEmpty();
        assertThat(auditIds("user_id = ? AND event_type = 'QUICK_MEMO_SETTINGS_UPDATED'", active)).isEmpty();
        assertThat(auditIds("event_type = 'WITHDRAWAL_COMPLETED' AND metadata = CAST(? AS JSON)", completionMetadata())).isEmpty();
        auditBaselineConfirmed = true;
        var deletedProbe = seed(victim);
        var activeProbe = seed(active);
        assertThat(count("SELECT COUNT(*) FROM event_publication_attempts WHERE status = 'PENDING'")).isEqualTo(2);
        userService.requestWithdrawal(victim, new RequestWithdrawalRequest(null));
        idle();
        withdrawalAuditIds.addAll(auditIds("user_id = ? AND event_type = 'WITHDRAWAL_REQUESTED'", victim));
        assertThat(withdrawalAuditIds).hasSize(1);
        jdbc.update("UPDATE users SET deleted_at = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id = ?", victim);
        assertThat(jdbc.queryForList("SELECT id FROM users WHERE deleted_at < DATE_SUB(NOW(), INTERVAL 30 DAY)", Long.class))
                .containsExactly(victim);
        purge.purgeExpiredAccounts();
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", victim)).isZero();
            assertThat(count("SELECT COUNT(*) FROM user_quick_memo_settings WHERE user_id = ?", victim)).isZero();
            assertThat(count("SELECT COUNT(*) FROM account_purge_completion_status WHERE user_id = ? "
                    + "AND domain_name = 'quickmemo' AND status = 'SUCCESS'", victim)).isEqualTo(1);
        });
        idle();
        completionAuditIds.addAll(auditIds("event_type = 'WITHDRAWAL_COMPLETED' AND metadata = CAST(? AS JSON)", completionMetadata()));
        assertThat(completionAuditIds).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL", active)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_quick_memo_settings WHERE user_id = ? AND reminder_enabled = false", active)).isEqualTo(1);
        replay.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(deletedProbe.skipped).hasValue(1);
            assertThat(activeProbe.written).hasValue(1);
        });
        idle();
        assertThat(deletedProbe.written).hasValue(0);
        assertThat(activeProbe.skipped).hasValue(0);
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", victim)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_quick_memo_settings WHERE user_id = ?", victim)).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL", active)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_quick_memo_settings WHERE user_id = ? AND reminder_enabled = true", active)).isEqualTo(1);
        for (UUID id : publicationIds) {
            assertThat(count("SELECT COUNT(*) FROM event_publication WHERE id = ?", binary(id))).isZero();
            assertThat(count("SELECT COUNT(*) FROM event_publication_attempts WHERE publication_id = ?", binary(id))).isZero();
        }
        settingsAuditIds.addAll(auditIds("user_id = ? AND event_type = 'QUICK_MEMO_SETTINGS_UPDATED'", active));
        assertThat(settingsAuditIds).hasSize(1);
    }

    private Long createUser(String name) {
        String email = "m16-" + UUID.randomUUID() + "@example.com";
        Long id = new TransactionTemplate(transactions).execute(tx -> {
            var user = users.saveAndFlush(UserEntity.builder().email(email).lastName("試練").firstName(name)
                    .displayName(name).status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build());
            settings.saveAndFlush(UserQuickMemoSettingsEntity.builder().userId(user.getId()).reminderEnabled(false).build());
            return user.getId();
        });
        if (owners.isEmpty()) emailHash = SessionHashUtil.hash(email);
        owners.add(id);
        return id;
    }

    private ModuleEventPurgeReplayTestFixture.Probe seed(Long owner) {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        var probe = listeners.register(eventId);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var publication = publications.create(TargetEventPublication.of(
                    new ModuleEventPurgeReplayTestFixture.PurgeReplayEvent(eventId, owner, LocalDateTime.now()),
                    PublicationTargetIdentifier.of(ModuleEventPurgeReplayTestFixture.LISTENER_ID), Instant.now().minus(Duration.ofDays(1))));
            publicationIds.add(publication.getIdentifier());
            new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, em).saveAndFlush(EventPublicationAttemptEntity.builder()
                    .publicationId(publication.getIdentifier()).retryCount(0).status(ModuleEventAttemptStatus.PENDING)
                    .claimToken(UUID.randomUUID()).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        });
        return probe;
    }

    @AfterEach
    void 自己検体だけを回収する() {
        idle();
        // 途中assert失敗でも、事前0を確認した自己検体だけをID集合へ記録する。
        if (auditBaselineConfirmed) {
            withdrawalAuditIds.addAll(auditIds("user_id = ? AND event_type = 'WITHDRAWAL_REQUESTED'", victim));
            settingsAuditIds.addAll(auditIds("user_id = ? AND event_type = 'QUICK_MEMO_SETTINGS_UPDATED'", active));
            completionAuditIds.addAll(auditIds("event_type = 'WITHDRAWAL_COMPLETED' AND metadata = CAST(? AS JSON)", completionMetadata()));
        }
        // 使い捨て検体の回収。監査の本番privacy/retention方針を変更するものではない。
        for (Long id : withdrawalAuditIds.stream().distinct().toList()) jdbc.update("DELETE FROM audit_logs WHERE id = ? AND (user_id = ? OR user_id IS NULL) AND event_type = 'WITHDRAWAL_REQUESTED'", id, victim);
        for (Long id : settingsAuditIds.stream().distinct().toList()) jdbc.update("DELETE FROM audit_logs WHERE id = ? AND user_id = ? AND event_type = 'QUICK_MEMO_SETTINGS_UPDATED'", id, active);
        for (Long id : completionAuditIds.stream().distinct().toList()) jdbc.update("DELETE FROM audit_logs WHERE id = ? AND event_type = 'WITHDRAWAL_COMPLETED' AND metadata = CAST(? AS JSON)", id, completionMetadata());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var attempts = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, em);
            for (UUID id : publicationIds) {
                var owned = em.createQuery("SELECT a FROM EventPublicationAttemptEntity a WHERE a.publicationId = :id", EventPublicationAttemptEntity.class)
                        .setParameter("id", id).getResultList();
                attempts.deleteAll(owned);
            }
            em.flush();
            publications.deletePublications(publicationIds);
            for (Long owner : owners) {
                settings.deleteById(owner);
                em.createQuery("DELETE FROM AccountPurgeCompletionStatusEntity c WHERE c.userId = :id").setParameter("id", owner).executeUpdate();
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", owner).executeUpdate();
            }
        });
        eventIds.forEach(listeners::remove);
    }

    private String completionMetadata() { return "{\"email_hash\":\"" + emailHash + "\"}"; }
    private List<Long> auditIds(String predicate, Object value) {
        return jdbc.queryForList("SELECT id FROM audit_logs WHERE " + predicate, Long.class, value);
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private void idle() {
        for (Executor executor : List.of(modulePool, purgePool, withdrawalPool, eventPool)) {
            var pool = ((ThreadPoolTaskExecutor) executor).getThreadPoolExecutor();
            await().atMost(WAIT).until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty()
                    && pool.getCompletedTaskCount() == pool.getTaskCount());
        }
    }
    private static byte[] binary(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
