package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.fanout.FanoutChunkSink;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 試練B: 配信のキャンセル・期限切れによる打ち切り（軍議第8版確定稿 §8.3・§9.1・§9.2・
 * AC-43・AC-44・AC-51・AC-53・AC-54）。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ConfirmableFanoutChunkSink キャンセル・期限切れによる配信打ち切り（AC-43/44/51/53/54・試練B）")
class ConfirmableFanoutChunkSinkCancelExpiryIT extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-stop";

    @Autowired
    private ConfirmableFanoutChunkSink sink;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationFanoutJobRepository fanoutJobRepository;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long notificationId;
    private UUID jobIdToCleanUp;

    @AfterEach
    void cleanUp() {
        if (jobIdToCleanUp != null) {
            fanoutJobRepository.deleteById(jobIdToCleanUp);
        }
        if (notificationId != null) {
            jdbc.update("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                    notificationId);
            jdbc.update("DELETE FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                    notificationId);
            jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", notificationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(em, emailPrefix);
        }
    }

    /**
     * 是正: finish は §9.2 の関所として notification_fanout_jobs 行を参照しジョブを DONE にする契約の
     * ため、存在しない jobId ではなく実在するジョブ行を先に用意してから finish を呼ぶ。
     */
    private void insertJobRow(UUID jobId, Long notificationId) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        fanoutJobRepository.save(NotificationFanoutJob.builder()
                .id(jobId)
                .sourceEventUuid(UUID.randomUUID())
                .scopeType("CONFIRMABLE_TARGETS")
                .scopeRef(String.valueOf(notificationId))
                .notificationType("CONFIRMABLE_NOTIFICATION_FANOUT")
                .sourceType("CONFIRMABLE_NOTIFICATION")
                .sourceId(notificationId)
                .status(NotificationFanoutJobStatus.RUNNING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .nextAttemptAt(now)
                .priority(com.mannschaft.app.notification.NotificationPriority.NORMAL)
                .createdAt(now)
                .updatedAt(now)
                .build());
        jobIdToCleanUp = jobId;
    }

    private Long createNotification(ConfirmableNotificationStatus status) {
        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-43/44/51/53/54 打ち切り")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(status)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.QUEUED)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        return notification.getId();
    }

    @Test
    @DisplayName("AC-43: QUEUEDの段階でCANCELLEDされていると、最初のチャンクは1件も作らずstopped=true")
    void cancelledBeforeAnyChunkStopsImmediately() {
        emailPrefix = EMAIL_PREFIX_BASE + "-43-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 10, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.CANCELLED);
        UUID jobId = UUID.randomUUID();

        FanoutChunkSink.ChunkResult result = sink.processChunk(jobId, notificationId, userIds);

        assertThat(result.stopped()).as("AC-43: CANCELLED状態のチャンクは打ち切られる").isTrue();
        assertThat(result.addedCount()).isEqualTo(0);
        assertThat(countRecipients()).isEqualTo(0L);
        assertThat(countNotifications()).isEqualTo(0L);
    }

    @Test
    @DisplayName("AC-44: 配信の途中でキャンセルされると、それ以降のチャンクは作られないが既存分は残る")
    void cancelMidwayStopsSubsequentChunksButKeepsExisting() {
        emailPrefix = EMAIL_PREFIX_BASE + "-44-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 20, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE);
        UUID jobId = UUID.randomUUID();

        List<Long> chunk1 = userIds.subList(0, 10);
        List<Long> chunk2 = userIds.subList(10, 20);

        FanoutChunkSink.ChunkResult r1 = sink.processChunk(jobId, notificationId, chunk1);
        assertThat(r1.stopped()).isFalse();
        assertThat(r1.addedCount()).isEqualTo(10);

        // チャンク1の後、チャンク2の前にキャンセルする。
        jdbc.update("UPDATE confirmable_notifications SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = ?",
                notificationId);

        FanoutChunkSink.ChunkResult r2 = sink.processChunk(jobId, notificationId, chunk2);
        assertThat(r2.stopped()).as("AC-44: キャンセル後のチャンクは打ち切られる").isTrue();
        assertThat(r2.addedCount()).isEqualTo(0);

        assertThat(countRecipients()).as("AC-44: 既に作った分（チャンク1の10件）は残る").isEqualTo(10L);
    }

    @Test
    @DisplayName("AC-51: 配信の途中でEXPIREDになった場合も、それ以降のチャンクは作られない")
    void expiredMidwayStopsSubsequentChunks() {
        emailPrefix = EMAIL_PREFIX_BASE + "-51-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 20, emailPrefix);
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE);
        UUID jobId = UUID.randomUUID();

        List<Long> chunk1 = userIds.subList(0, 10);
        List<Long> chunk2 = userIds.subList(10, 20);

        sink.processChunk(jobId, notificationId, chunk1);

        jdbc.update("UPDATE confirmable_notifications SET status = 'EXPIRED', expired_at = NOW() WHERE id = ?",
                notificationId);

        FanoutChunkSink.ChunkResult r2 = sink.processChunk(jobId, notificationId, chunk2);
        assertThat(r2.stopped()).as("AC-51: EXPIRED後のチャンクは打ち切られる").isTrue();
        assertThat(r2.addedCount()).isEqualTo(0);
        assertThat(countRecipients()).isEqualTo(10L);
    }

    @Test
    @DisplayName("AC-53: QUEUEDの段階でキャンセルされ最初のページが空だった場合、"
            + "finishはdelivery_status=STOPPEDにし、DELIVEREDにはしない")
    void finishAfterCancelWithNoChunksSetsStopped() {
        emailPrefix = EMAIL_PREFIX_BASE + "-53-" + UUID.randomUUID();
        notificationId = createNotification(ConfirmableNotificationStatus.CANCELLED);
        UUID jobId = UUID.randomUUID();
        insertJobRow(jobId, notificationId);

        sink.finish(jobId, notificationId);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getDeliveryStatus()).as("AC-53: STOPPEDになる（DELIVEREDにはならない）")
                .isEqualTo(ConfirmableNotificationDeliveryStatus.STOPPED);
        assertThat(after.getStatus()).isEqualTo(ConfirmableNotificationStatus.CANCELLED);

        NotificationFanoutJob job = fanoutJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).as("AC-53: ジョブはDONEになる（DELIVEREDにはならない）")
                .isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    @Test
    @DisplayName("AC-54: 受信者が0人のまま配信を終えた場合、delivery_status=DELIVERED・"
            + "total_recipient_count=0・statusはACTIVEのまま（COMPLETEDにしない）")
    void finishWithZeroRecipientsStaysActiveNotCompleted() {
        emailPrefix = EMAIL_PREFIX_BASE + "-54-" + UUID.randomUUID();
        notificationId = createNotification(ConfirmableNotificationStatus.ACTIVE);
        UUID jobId = UUID.randomUUID();
        insertJobRow(jobId, notificationId);

        sink.finish(jobId, notificationId);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getDeliveryStatus()).isEqualTo(ConfirmableNotificationDeliveryStatus.DELIVERED);
        assertThat(after.getTotalRecipientCount()).isEqualTo(0);
        assertThat(after.getStatus())
                .as("AC-54: 0人に対して『全員確認済み』を成立させないのでACTIVEのまま")
                .isEqualTo(ConfirmableNotificationStatus.ACTIVE);

        NotificationFanoutJob job = fanoutJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).as("finish後はジョブもDONE").isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    private long countRecipients() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        return c == null ? 0 : c;
    }

    private long countNotifications() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                Long.class, notificationId);
        return c == null ? 0 : c;
    }
}
