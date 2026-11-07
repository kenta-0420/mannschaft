package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxEntity;
import com.mannschaft.app.notification.service.ModuleEventNoticeArchiveTestBridge;
import com.mannschaft.app.notification.service.NotificationCleanupBatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実 MySQL の同 job 排他・commit・current read・再試行を検査する先行試練。 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeNotificationFixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeFanoutChunkSinkIT extends ModuleEventNoticeNotificationTestSupport {
    @Autowired ModuleEventNoticeFanoutChunkSink sink;
    @Autowired @Qualifier("brokerChannel") AbstractMessageChannel broker;
    @Autowired ObjectMapper mapper;
    @Autowired NotificationCleanupBatchService cleanup;

    @Test
    void broker配信を観測した時点で生成IDが別connectionからcommit済みである() throws Exception {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        CountDownLatch observed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        ChannelInterceptor observer = new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (!String.valueOf(message.getHeaders().get("simpDestination")).contains("/" + USER_A + "/queue/notifications")) {
                    return message;
                }
                boolean ownNotification = false;
                try {
                    Object payload = message.getPayload();
                    var json = payload instanceof byte[] bytes ? mapper.readTree(bytes)
                            : payload instanceof String text ? mapper.readTree(text) : mapper.valueToTree(payload);
                    if (json.path("userId").asLong() != USER_A || json.path("sourceId").asLong() != row.getNoticeRef()
                            || !"MODULE_EVENT_NOTICE".equals(json.path("sourceType").asText())) return message;
                    ownNotification = true;
                    long notificationId = json.path("id").asLong();
                    assertThat(notificationId).isPositive();
                    // Spring の caller TX/connection を共有しない物理別接続から commit 可視性を測る。
                    try (Connection connection = openOwnConnection(false);
                            PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM notifications "
                                    + "WHERE id = ? AND source_type = ? AND source_id = ? AND user_id = ?")) {
                        query.setQueryTimeout(1);
                        query.setLong(1, notificationId);
                        query.setString(2, "MODULE_EVENT_NOTICE");
                        query.setLong(3, row.getNoticeRef());
                        query.setLong(4, USER_A);
                        try (ResultSet result = query.executeQuery()) {
                            result.next();
                            assertThat(result.getLong(1)).isEqualTo(1);
                        }
                    }
                } catch (Throwable problem) { failure.set(problem); ownNotification = true; }
                finally { if (ownNotification) observed.countDown(); }
                return message;
            }
        };
        broker.addInterceptor(observer);
        try {
            assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isEqualTo(1);
            assertThat(observed.await(30, TimeUnit.SECONDS)).as("実 broker へ届く採番済み通知").isTrue();
            if (failure.get() instanceof AssertionError assertion) throw assertion;
            if (failure.get() != null) throw new IllegalStateException("自己 commit 可視性の観測に失敗", failure.get());
        } finally { broker.removeInterceptor(observer); }
    }

    @Test
    void 新規だけ同一TXで採番保存し現在localeと業務保存scopeを使う() {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "en"));
        UUID id = job(row);
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A, USER_A)).addedCount()).isEqualTo(1);
        var stored = jdbc.queryForMap("SELECT * FROM notifications WHERE source_type = ? AND source_id = ?",
                "MODULE_EVENT_NOTICE", row.getNoticeRef());
        assertThat(((Number) stored.get("id")).longValue()).isPositive();
        assertThat(stored.get("title")).isEqualTo("title-en");
        assertThat(stored.get("scope_type")).isEqualTo("TEAM");
        assertThat(((Number) stored.get("scope_id")).longValue()).isEqualTo(8201L);
        assertThat(stored.get("actor_id")).isNull();
        assertThat(stored.get("action_url")).isNull();
        assertThat(state(row).observedOutsideTransaction).isTrue();
    }

    @Test
    void cursor前crashの同候補再試行は追加0となる() {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isEqualTo(1);
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isZero();
        assertThat(notificationCount(row)).isEqualTo(1);
    }

    @Test
    void 同job同候補二threadは排他され新規合計1となる() throws Exception {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)); });
            var second = executor.submit(() -> { start.await(); return sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)); });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).addedCount() + second.get(30, TimeUnit.SECONDS).addedCount())
                    .isEqualTo(1);
        }
        assertThat(notificationCount(row)).isEqualTo(1);
    }

    @Test
    void 本体がarchiveへ移送された後も再作成しない() {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isEqualTo(1);
        archiveOwnNotice(row);
        tx(() -> jdbc.update("DELETE FROM notifications WHERE source_type = ? AND source_id = ?",
                "MODULE_EVENT_NOTICE", row.getNoticeRef()));
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isZero();
        assertThat(notificationCount(row)).isZero();
    }

    @Test
    void 移送中は本体currentlockを待ちcommit後archiveを再確認する() throws Exception {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isEqualTo(1);
        // 製品と同じ順序で archive INSERT を独立 commit し、続く自己 live DELETE の TX だけ hold する。
        long notificationId = archiveOwnNotice(row);
        var moving = new java.util.concurrent.CompletableFuture<Long>();
        var release = new java.util.concurrent.CompletableFuture<Boolean>();
        var executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> mover = null;
        java.util.concurrent.Future<com.mannschaft.app.notification.fanout.FanoutChunkSink.ChunkResult> retry = null;
        try (Connection observer = openOwnConnection(true)) {
            preflight(observer);
            mover = executor.submit(() -> tx(() -> {
                long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                int deleted = jdbc.update("DELETE FROM notifications WHERE id = ? AND source_type = ? AND source_id = ?",
                        notificationId, "MODULE_EVENT_NOTICE", row.getNoticeRef());
                if (deleted != 1) throw new IllegalStateException("自己 live 行の DELETE 前提を満たさない");
                moving.complete(connectionId);
                try { release.get(45, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException("自己 DELETE の解除が届かない", failure); }
                return null;
            }));
            long blocker = moving.get(15, TimeUnit.SECONDS);
            retry = executor.submit(() -> sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)));
            var worker = retry;
            org.awaitility.Awaitility.await().pollInSameThread().pollInterval(java.time.Duration.ofMillis(25))
                    .atMost(java.time.Duration.ofSeconds(10)).until(() -> {
                        if (worker.isDone()) {
                            worker.get();
                            throw new IllegalStateException("自己本体行の実 lock 待ちより前に retry が終了した");
                        }
                        return ownNotificationWaits(observer, blocker, notificationId) > 0;
                    });
            release.complete(true);
            mover.get(30, TimeUnit.SECONDS);
            assertThat(retry.get(30, TimeUnit.SECONDS).addedCount()).isZero();
            assertThat(notificationCount(row)).isZero();
        } finally {
            release.complete(true);
            executor.shutdown();
            try {
                if (mover != null) mover.get(60, TimeUnit.SECONDS);
                if (retry != null) retry.get(60, TimeUnit.SECONDS);
            } finally {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    throw new IllegalStateException("自己 worker が終了せず cleanup 前提を満たさない");
                }
            }
        }
    }

    private long archiveOwnNotice(ModuleEventNoticeOutboxEntity row) {
        Long otherEligible = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE "
                + "((is_read = TRUE AND created_at < UTC_TIMESTAMP() - INTERVAL 90 DAY) OR "
                + "(is_read = FALSE AND created_at < UTC_TIMESTAMP() - INTERVAL 365 DAY)) AND "
                + "(source_type IS NULL OR source_type <> ? OR source_id IS NULL OR source_id <> ?)",
                Long.class, "MODULE_EVENT_NOTICE", row.getNoticeRef());
        if (otherEligible == null || otherEligible != 0) {
            throw new IllegalStateException("自己対象以外の既存 archive 適格行があるため製品バッチを呼ばない");
        }
        long notificationId = jdbc.queryForObject("SELECT id FROM notifications WHERE source_type = ? AND source_id = ?",
                Long.class, "MODULE_EVENT_NOTICE", row.getNoticeRef());
        int changed = jdbc.update("UPDATE notifications SET created_at = UTC_TIMESTAMP() - INTERVAL 400 DAY "
                + "WHERE id = ? AND source_type = ? AND source_id = ?", notificationId, "MODULE_EVENT_NOTICE", row.getNoticeRef());
        if (changed != 1) throw new IllegalStateException("自己通知の aging 対象が1件でない");
        if (ModuleEventNoticeArchiveTestBridge.insertIntoArchive(cleanup) != 1) {
            throw new IllegalStateException("実製品 archive INSERT の自己対象前提を満たさない");
        }
        return notificationId;
    }

    /** 自己 MYSQL の公開 getter を使い、Spring TX を共有しない read-only 物理接続を開く。 */
    private Connection openOwnConnection(boolean rootObserver) throws SQLException {
        String url = MYSQL.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?")
                + "connectTimeout=1000&socketTimeout=1000&useSSL=false&allowPublicKeyRetrieval=true";
        Connection connection = DriverManager.getConnection(url,
                rootObserver ? "root" : MYSQL.getUsername(), MYSQL.getPassword());
        try { connection.setReadOnly(true); return connection; }
        catch (SQLException failure) { connection.close(); throw failure; }
    }

    private static void preflight(Connection observer) throws SQLException {
        for (String table : List.of("data_locks", "data_lock_waits", "threads")) {
            try (PreparedStatement query = observer.prepareStatement("SELECT COUNT(*) FROM performance_schema." + table + " WHERE 1 = 0")) {
                query.setQueryTimeout(1);
                try (ResultSet ignored = query.executeQuery()) { /* SQL/権限不足は worker 開始前に OTHER とする。 */ }
            }
        }
    }

    private long ownNotificationWaits(Connection observer, long blocker, long rowId) throws SQLException {
        try (PreparedStatement query = observer.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested ON requested.ENGINE = waits.ENGINE
                  AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.threads blocking ON blocking.THREAD_ID = waits.BLOCKING_THREAD_ID
                WHERE requested.OBJECT_SCHEMA = ? AND requested.OBJECT_NAME = 'notifications'
                  AND requested.INDEX_NAME = 'PRIMARY' AND requested.LOCK_TYPE = 'RECORD'
                  AND requested.LOCK_STATUS = 'WAITING'
                  AND requested.LOCK_DATA = ? AND blocking.PROCESSLIST_ID = ?
                """)) {
            query.setQueryTimeout(1);
            query.setString(1, MYSQL.getDatabaseName());
            query.setString(2, Long.toString(rowId));
            query.setLong(3, blocker);
            try (ResultSet result = query.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    @Test
    void 現在不適格となった候補や別候補は作らない() {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_B, "ja"));
        UUID id = job(row);
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A, USER_B)).addedCount()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT user_id FROM notifications WHERE source_type = ? AND source_id = ?",
                Long.class, "MODULE_EVENT_NOTICE", row.getNoticeRef())).containsExactly(USER_B);
    }

    @Test
    void 未接続は通知を作らず正常終了ともみなさない() {
        var row = notice("DELAYED");
        state(row).available = false;
        UUID id = job(row);
        assertThatThrownBy(() -> sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(notificationCount(row)).isZero();
    }

    @Test
    void 他noticeのjobと入力refの不一致を拒否する() {
        var first = notice("DELAYED");
        var second = notice("QUARANTINED");
        state(second).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(first);
        assertThatThrownBy(() -> sink.processChunk(id, second.getNoticeRef(), List.of(USER_A)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(notificationCount(first) + notificationCount(second)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"source_type", "notification_type", "scope_ref", "organization_id"})
    void jobの型scope又はtenantが不一致なら保存しない(String column) {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        Object value = column.equals("organization_id") ? 9999L : column.equals("scope_ref") ? "9999" : "SYSTEM";
        jdbc.update("UPDATE notification_fanout_jobs SET " + column + " = ? WHERE id = ?", value, bytes(id));
        assertThatThrownBy(() -> sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(notificationCount(row)).isZero();
    }

    @Test
    void DONE後の古いworkerは追加も再dispatchも行わない() {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        sink.finish(id, row.getNoticeRef());
        assertThat(jdbc.queryForObject("SELECT status FROM notification_fanout_jobs WHERE id = ?", String.class, bytes(id)))
                .isEqualTo("DONE");
        assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isZero();
        assertThat(notificationCount(row)).isZero();
    }

    @Test
    void 文面破損で途中失敗した場合チャンク全体をrollbackする() {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"), new ModuleEventNoticeRecipient(USER_B, "en"));
        UUID id = job(row);
        jdbc.update("DELETE FROM notification_fanout_job_messages WHERE job_id = ?", bytes(id));
        assertThatThrownBy(() -> sink.processChunk(id, row.getNoticeRef(), List.of(USER_A, USER_B)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(notificationCount(row)).isZero();
    }

    @Test
    void 二人目INSERT失敗では一人目もcommitされない() throws SQLException {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"), new ModuleEventNoticeRecipient(USER_B, "en"));
        UUID id = job(row);
        String trigger = "l1m_notice_fail_" + UUID.randomUUID().toString().replace("-", "");
        // 自予約・自候補の二人目だけを失敗させる。既存利用者の INSERT には作用しない。
        executeOwnTriggerDdl(trigger, row.getNoticeRef(), true);
        try {
            assertThatThrownBy(() -> sink.processChunk(id, row.getNoticeRef(), List.of(USER_A, USER_B)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(notificationCount(row)).isZero();
        } finally { executeOwnTriggerDdl(trigger, row.getNoticeRef(), false); }
    }

    /** 自己検体のtrigger DDLだけに使う書込接続。read-onlyのlock observerと共用しない。 */
    private void executeOwnTriggerDdl(String trigger, long noticeRef, boolean create) throws SQLException {
        if (!trigger.matches("l1m_notice_fail_[0-9a-f]{32}") || noticeRef <= 0) {
            throw new IllegalArgumentException("自己triggerの識別子・sourceRef前提を満たさない");
        }
        String url = MYSQL.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?")
                + "connectTimeout=1000&socketTimeout=1000&useSSL=false&allowPublicKeyRetrieval=true";
        // binlog有効な自己MySQLでCREATE/DROPに必要な権限だけを使う。global設定は変更しない。
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                java.sql.Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            statement.execute(create ? "CREATE TRIGGER " + trigger + " BEFORE INSERT ON notifications FOR EACH ROW BEGIN "
                    + "IF NEW.source_type = 'MODULE_EVENT_NOTICE' AND NEW.source_id = " + noticeRef
                    + " AND NEW.user_id = " + USER_B
                    + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'own notice failure'; END IF; END"
                    : "DROP TRIGGER " + trigger);
        }
    }

}
