package com.mannschaft.app.notification.moduleevent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.notification.repository.NotificationRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

/**
 * 実WorkerのDONE再処理とreceipt不在時の再生成境界を検証する。
 * 通知削除は既存Repositoryで自己IDに限定した検体操作。公開ユーザー削除APIの証明ではない。
 * 自動物理削除や再開履歴PIIの保持方針を追加・変更する試練ではない。
 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeNotificationFixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeWorkerLifecycleIT extends ModuleEventNoticeNotificationTestSupport {
    @Autowired ModuleEventNoticeOutboxSource source;
    @Autowired NotificationFanoutWorker worker;
    @Autowired NotificationFanoutJobRepository jobRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired @Qualifier("brokerChannel") AbstractMessageChannel broker;
    @Autowired @Qualifier("notification-fanout-pool") Executor fanoutPool;
    @Autowired ObjectMapper mapper;

    @Test
    void 全予約状態のsweepは例外なく0を返しdescriptorを保持する() {
        for (String status : List.of("WAITING_RECOVERY", "PENDING", "RELAYING", "RELAYED", "DEAD")) {
            String phase = status.equals("WAITING_RECOVERY") ? "RECOVERED"
                    : status.equals("RELAYED") || status.equals("DEAD") ? "QUARANTINED" : "DELAYED";
            var row = notice(phase, status, 1, "TEAM");
            // 自己予約だけを期限前に置き、時刻だけで消す実装も検出する。
            jdbc.update("UPDATE module_event_notice_outbox SET claimed_at = ?, claim_token = ?, relayed_at = ?, dead_at = ? WHERE id = ?",
                    status.equals("RELAYING") ? Timestamp.from(FUTURE) : null,
                    status.equals("RELAYING") ? bytes(UUID.randomUUID()) : null,
                    status.equals("RELAYED") ? Timestamp.from(FUTURE) : null,
                    status.equals("DEAD") ? Timestamp.from(FUTURE) : null, bytes(row.getId()));
        }
        var before = ownReservations();
        assertThat(before).hasSize(5);
        AtomicInteger swept = new AtomicInteger(-1);
        // 現UnsupportedOperationExceptionはOTHERに逃がさずassertionとして観測する。
        assertThatCode(() -> swept.set(source.sweep(FUTURE.plusSeconds(86400), FUTURE.plusSeconds(86400))))
                .doesNotThrowAnyException();
        assertThat(swept).hasValue(0);
        assertThat(ownReservations()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void DONE後に自己通知receiptを除去しても古いdetachedjobの実再処理は再生成も再dispatchもしない() {
        idle();
        var row = notice("DELAYED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        UUID id = job(row);
        // Workerが引数のcursorを変更するため、初回実行前に別々の実体を確保する。
        NotificationFanoutJob first = detachedJob(id);
        NotificationFanoutJob stale = detachedJob(id);
        assertThat(first).isNotSameAs(stale);
        assertThat(first.getCursorSubjectId()).isZero();
        assertThat(stale.getCursorSubjectId()).isZero();
        List<Long> sentIds = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> observationFailure = new AtomicReference<>();
        ChannelInterceptor observer = new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (!("/user/" + USER_A + "/queue/notifications").equals(message.getHeaders().get("simpDestination"))) return message;
                try {
                    Object payload = message.getPayload();
                    var json = payload instanceof byte[] value ? mapper.readTree(value)
                            : payload instanceof String value ? mapper.readTree(value) : mapper.valueToTree(payload);
                    if (json.path("userId").asLong() == USER_A && json.path("sourceId").asLong() == row.getNoticeRef()
                            && "MODULE_EVENT_NOTICE".equals(json.path("sourceType").asText())) sentIds.add(json.path("id").asLong());
                } catch (Throwable failure) { observationFailure.set(failure); }
                return message;
            }
        };
        broker.addInterceptor(observer);
        try {
            worker.processOne(first);
            idle();
            assertThat(jobStatus(id)).isEqualTo("DONE");
            assertThat(notificationCount(row)).isEqualTo(1);
            Long notificationId = jdbc.queryForObject("SELECT id FROM notifications WHERE user_id = ? AND source_type = ? AND source_id = ?",
                    Long.class, USER_A, "MODULE_EVENT_NOTICE", row.getNoticeRef());
            assertThat(notificationId).isPositive();
            requireObservation(observationFailure);
            assertThat(sentIds).containsExactly(notificationId);
            assertThat(ownArchiveCount(row.getNoticeRef())).isZero();
            tx(() -> {
                var own = notificationRepository.findByIdAndUserId(notificationId, USER_A).orElseThrow();
                notificationRepository.delete(own);
                notificationRepository.flush();
                return null;
            });
            assertThat(notificationCount(row)).isZero();
            assertThat(ownArchiveCount(row.getNoticeRef())).isZero();
            assertThat(stale.getCursorSubjectId()).as("処理前に取得したcursor0の旧コピー").isZero();
            worker.processOne(stale);
            idle();
            requireObservation(observationFailure);
            assertSoftly(softly -> {
                softly.assertThat(notificationCount(row)).as("DONE後・receipt不在でも再生成0").isZero();
                softly.assertThat(sentIds).as("実brokerへの再dispatch0").containsExactly(notificationId);
                softly.assertThat(ownArchiveCount(row.getNoticeRef())).isZero();
                softly.assertThat(jobStatus(id)).isEqualTo("DONE");
            });
        } finally {
            try { idle(); } finally { broker.removeInterceptor(observer); }
        }
    }

    private List<Map<String,Object>> ownReservations() {
        var rows = new ArrayList<Map<String,Object>>();
        for (var row : notices) rows.add(jdbc.queryForMap("SELECT * FROM module_event_notice_outbox WHERE id = ?", bytes(row.getId())));
        return rows;
    }
    private NotificationFanoutJob detachedJob(UUID id) {
        return tx(() -> {
            var result = jobRepository.findById(id).orElseThrow();
            entityManager.detach(result);
            return result;
        });
    }
    private String jobStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notification_fanout_jobs WHERE id = ?", String.class, bytes(id));
    }
    private long ownArchiveCount(Long reference) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications_archive WHERE user_id = ? AND source_type = ? AND source_id = ?",
                Long.class, USER_A, "MODULE_EVENT_NOTICE", reference);
    }
    private static void requireObservation(AtomicReference<Throwable> failure) {
        if (failure.get() != null) throw new IllegalStateException("自己broker payloadの観測に失敗", failure.get());
    }
    private void idle() {
        var pool = ((ThreadPoolTaskExecutor) fanoutPool).getThreadPoolExecutor();
        await().atMost(Duration.ofSeconds(30)).until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty()
                && pool.getCompletedTaskCount() == pool.getTaskCount());
    }
}
