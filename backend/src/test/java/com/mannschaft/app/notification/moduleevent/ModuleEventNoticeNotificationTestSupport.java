package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxEntity;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobMessage;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 自分の通知予約・job・配信行だけを生成・撤去する実 MySQL 共通補助。 */
abstract class ModuleEventNoticeNotificationTestSupport extends AbstractMySqlIntegrationTest {
    static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");
    static final long USER_A = 910001L;
    static final long USER_B = 910002L;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ModuleEventNoticeNotificationFixtures.Resolver resolver;
    @PersistenceContext EntityManager entityManager;
    final List<ModuleEventNoticeOutboxEntity> notices = new ArrayList<>();
    final List<UUID> jobs = new ArrayList<>();

    @BeforeEach
    void 既存金型と同じ製品archiveDDLを未作成時だけ適用する() throws java.io.IOException {
        synchronized (ModuleEventNoticeNotificationTestSupport.class) {
            if (archiveExists()) return;
            var resource = new ClassPathResource("db/migration/V173.20260730033807__create_notifications_archive_and_read_index.sql");
            String sql = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            int start = sql.indexOf("CREATE TABLE notifications_archive (");
            int end = sql.indexOf(";", start);
            if (start < 0 || end < start) throw new IllegalStateException("製品 archive DDL が見つからない");
            // 原文の CREATE TABLE 文だけを採用する。既存表・既存索引を更新しない。
            jdbc.execute(sql.substring(start, end + 1));
        }
    }

    @AfterEach
    void 自分の生成行だけを撤去する() {
        for (ModuleEventNoticeOutboxEntity notice : notices) {
            jdbc.update("DELETE FROM notifications WHERE source_type = ? AND source_id = ?",
                    "MODULE_EVENT_NOTICE", notice.getNoticeRef());
            if (archiveExists()) jdbc.update("DELETE FROM notifications_archive WHERE source_type = ? AND source_id = ?",
                    "MODULE_EVENT_NOTICE", notice.getNoticeRef());
            jdbc.update("DELETE FROM notification_fanout_job_messages WHERE job_id IN "
                    + "(SELECT id FROM notification_fanout_jobs WHERE source_type = ? AND source_id = ?)",
                    "MODULE_EVENT_NOTICE", notice.getNoticeRef());
            jdbc.update("DELETE FROM notification_fanout_jobs WHERE source_type = ? AND source_id = ?",
                    "MODULE_EVENT_NOTICE", notice.getNoticeRef());
        }
        for (UUID id : jobs) {
            jdbc.update("DELETE FROM notification_fanout_job_messages WHERE job_id = ?", bytes(id));
            jdbc.update("DELETE FROM notification_fanout_jobs WHERE id = ?", bytes(id));
        }
        for (ModuleEventNoticeOutboxEntity notice : notices) {
            tx(() -> { entityManager.createQuery("delete from ModuleEventNoticeOutboxEntity n where n.id = :id")
                    .setParameter("id", notice.getId()).executeUpdate(); return null; });
            resolver.operations.remove(notice.getOperationId());
        }
    }

    ModuleEventNoticeOutboxEntity notice(String phase) { return notice(phase, "PENDING", 1, "TEAM"); }

    ModuleEventNoticeOutboxEntity notice(String phase, String status, int version, String scope) {
        UUID operation = UUID.randomUUID();
        resolver.operations.put(operation, new ModuleEventNoticeNotificationFixtures.State());
        ModuleEventNoticeOutboxEntity row = tx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class,
                entityManager).saveAndFlush(ModuleEventNoticeOutboxEntity.builder().id(UuidV7.generate())
                .noticeKey(UUID.randomUUID()).resolverKey(ModuleEventNoticeNotificationFixtures.KEY)
                .operationId(operation).episodeId(UUID.randomUUID()).scopeType(scope).scopeId(8201L)
                .organizationId(null).phase(phase).audience("CURRENT_ADMINS").descriptorVersion(version)
                .status(status).attemptCount(0).nextAttemptAt(FUTURE).createdAt(FUTURE).updatedAt(FUTURE).build()));
        notices.add(row);
        return row;
    }

    ModuleEventNoticeNotificationFixtures.State state(ModuleEventNoticeOutboxEntity row) {
        return resolver.operations.get(row.getOperationId());
    }

    UUID job(ModuleEventNoticeOutboxEntity notice) {
        LocalDateTime now = LocalDateTime.of(2100, 1, 1, 0, 0);
        UUID id = UuidV7.generate();
        tx(() -> {
            new SimpleJpaRepository<>(NotificationFanoutJob.class, entityManager).saveAndFlush(
                    NotificationFanoutJob.builder().id(id).sourceEventUuid(notice.getNoticeKey())
                    .scopeType("MODULE_EVENT_NOTICE").scopeRef(notice.getNoticeRef().toString())
                    .notificationType("MODULE_EVENT_NOTICE").priority(NotificationPriority.NORMAL)
                    .sourceType("MODULE_EVENT_NOTICE").sourceId(notice.getNoticeRef())
                    .status(NotificationFanoutJobStatus.RUNNING).cursorSubjectId(0).insertedCount(0)
                    .retryCount(0).includeSupporters(false).shardIndex((short) 0).shardCount((short) 1)
                    .nextAttemptAt(now).createdAt(now).updatedAt(now).build());
            for (String locale : List.of("ja", "en", "zh", "ko", "es", "de")) {
                new SimpleJpaRepository<>(NotificationFanoutJobMessage.class, entityManager).saveAndFlush(
                        NotificationFanoutJobMessage.builder().id(UuidV7.generate()).jobId(id)
                        .locale(locale).title("title-" + locale).body("body-" + locale).build());
            }
            return null;
        });
        jobs.add(id);
        return id;
    }

    long notificationCount(ModuleEventNoticeOutboxEntity notice) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE source_type = ? AND source_id = ?",
                Long.class, "MODULE_EVENT_NOTICE", notice.getNoticeRef());
    }

    boolean archiveExists() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'notifications_archive'", Long.class) == 1;
    }

    <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
