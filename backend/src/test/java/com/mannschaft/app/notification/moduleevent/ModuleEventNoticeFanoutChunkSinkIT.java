package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
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
                try {
                    Object payload = message.getPayload();
                    var json = payload instanceof byte[] bytes ? mapper.readTree(bytes)
                            : payload instanceof String text ? mapper.readTree(text) : mapper.valueToTree(payload);
                    long notificationId = json.path("id").asLong();
                    assertThat(notificationId).isPositive();
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id = ? AND source_id = ?",
                            Long.class, notificationId, row.getNoticeRef())).isEqualTo(1);
                } catch (Throwable problem) { failure.set(problem); }
                finally { observed.countDown(); }
                return message;
            }
        };
        broker.addInterceptor(observer);
        try {
            assertThat(sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)).addedCount()).isEqualTo(1);
            assertThat(observed.await(30, TimeUnit.SECONDS)).as("実 broker へ届く採番済み通知").isTrue();
            assertThat(failure.get()).isNull();
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
        // archive は Entity 管理外。実際の製品 migration を採った schema が必要。
        assertThat(archiveExists()).as("notifications_archive の製品 schema が必要").isTrue();
        tx(() -> { jdbc.update("INSERT INTO notifications_archive "
                + "(id,user_id,organization_id,notification_type,priority,title,body,source_type,source_id,scope_type,scope_id,"
                + "action_url,actor_id,is_read,created_at,archived_at) SELECT id,user_id,organization_id,notification_type,"
                + "priority,title,body,source_type,source_id,scope_type,scope_id,action_url,actor_id,is_read,created_at,"
                + "UTC_TIMESTAMP() FROM notifications WHERE source_type = ? AND source_id = ?",
                "MODULE_EVENT_NOTICE", row.getNoticeRef()); return null; });
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
        CountDownLatch moving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var mover = executor.submit(() -> tx(() -> {
                jdbc.queryForList("SELECT id FROM notifications WHERE source_type = ? AND source_id = ? FOR UPDATE",
                        "MODULE_EVENT_NOTICE", row.getNoticeRef());
                jdbc.update("INSERT INTO notifications_archive "
                        + "(id,user_id,organization_id,notification_type,priority,title,body,source_type,source_id,scope_type,scope_id,"
                        + "action_url,actor_id,is_read,created_at,archived_at) SELECT id,user_id,organization_id,notification_type,"
                        + "priority,title,body,source_type,source_id,scope_type,scope_id,action_url,actor_id,is_read,created_at,"
                        + "UTC_TIMESTAMP() FROM notifications WHERE source_type = ? AND source_id = ?",
                        "MODULE_EVENT_NOTICE", row.getNoticeRef());
                jdbc.update("DELETE FROM notifications WHERE source_type = ? AND source_id = ?",
                        "MODULE_EVENT_NOTICE", row.getNoticeRef());
                moving.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("自移送の解除が届かない");
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return null;
            }));
            try {
                assertThat(moving.await(30, TimeUnit.SECONDS)).isTrue();
                var retry = executor.submit(() -> sink.processChunk(id, row.getNoticeRef(), List.of(USER_A)));
                // sleep による推測ではなく、実 MySQL の本体行ロック待ちを観測する。
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks "
                                + "WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = 'notifications' AND LOCK_STATUS = 'WAITING'",
                                Long.class)).isGreaterThan(0));
                release.countDown();
                mover.get(30, TimeUnit.SECONDS);
                assertThat(retry.get(30, TimeUnit.SECONDS).addedCount()).isZero();
                assertThat(notificationCount(row)).isZero();
            } finally { release.countDown(); }
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
    void 二人目INSERT失敗では一人目もcommitされない() {
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"), new ModuleEventNoticeRecipient(USER_B, "en"));
        UUID id = job(row);
        String trigger = "l1m_notice_fail_" + UUID.randomUUID().toString().replace("-", "");
        // 自予約・自候補の二人目だけを失敗させる。既存利用者の INSERT には作用しない。
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON notifications FOR EACH ROW BEGIN "
                + "IF NEW.source_type = 'MODULE_EVENT_NOTICE' AND NEW.source_id = " + row.getNoticeRef()
                + " AND NEW.user_id = " + USER_B + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'own notice failure'; END IF; END");
        try {
            assertThatThrownBy(() -> sink.processChunk(id, row.getNoticeRef(), List.of(USER_A, USER_B)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(notificationCount(row)).isZero();
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
    }
}
