package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.fanout.FanoutChunkSink;
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
 * CMP-260920-1040 試練B: チャンクを「INSERT確定後・カーソル前進前」でクラッシュしたあと、
 * 同じチャンクを再処理しても二重にならないことの検証（軍議第8版確定稿 §3.4手順1・§4 AC-23・
 * §8.4 AC-46/AC-47）。
 *
 * <p>本試練の担当外であるワーカーのカーソル管理は使わず、
 * {@link ConfirmableFanoutChunkSink#processChunk} を<b>同じ jobId・同じ user_id 集合</b>で
 * 2 回連続呼ぶことで「カーソルを進めずに同じチャンクを再処理させる」状況を直接再現する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ConfirmableFanoutChunkSink チャンク再処理の冪等性（AC-23・AC-46・AC-47・試練B）")
class ConfirmableFanoutChunkSinkIdempotentReplayIT extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-replay";

    @Autowired
    private ConfirmableFanoutChunkSink sink;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long notificationId;

    @AfterEach
    void cleanUp() {
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

    @Test
    @DisplayName("AC-23/AC-46/AC-47: 同じチャンク（同一jobId・同一user_id集合）を2回処理しても"
            + "受信者行・notifications・outbox・unconfirmed_countは2倍にならない")
    void reprocessingSameChunkDoesNotDuplicateAnything() {
        emailPrefix = EMAIL_PREFIX_BASE + "-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 500, emailPrefix);
        assertThat(userIds).hasSize(500);

        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-23/46/47 チャンク再処理")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.QUEUED)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        notificationId = notification.getId();

        UUID jobId = UUID.randomUUID();

        // --- 1回目: チャンクのINSERTは確定した（コミット済み）が、
        //     カーソル前進前にクラッシュした状況を模擬（カーソルは worker 側の管理でありここでは進めない）。
        FanoutChunkSink.ChunkResult first = sink.processChunk(jobId, notificationId, userIds);
        assertThat(first.addedCount()).isEqualTo(500);

        long recipientsAfterFirst = countRecipients();
        long notificationsAfterFirst = countNotifications();
        long outboxAfterFirst = countOutbox();
        int unconfirmedAfterFirst = reload().getUnconfirmedCount();

        // --- 再開: カーソルが進んでいないため同じチャンク（同じjobId・同じuser_id集合）を再処理する。
        FanoutChunkSink.ChunkResult replay = sink.processChunk(jobId, notificationId, userIds);

        assertThat(replay.addedCount())
                .as("AC-23: 再処理では『新規分』が無いので addedCount は 0")
                .isEqualTo(0);

        assertThat(countRecipients())
                .as("AC-23: 受信者行は再処理後も500のまま（二重にならない）")
                .isEqualTo(recipientsAfterFirst).isEqualTo(500L);
        assertThat(countNotifications())
                .as("AC-23: notifications行も再処理後も500のまま")
                .isEqualTo(notificationsAfterFirst).isEqualTo(500L);
        assertThat(countOutbox())
                .as("AC-46/AC-47: メールoutboxの行も再処理後に増えない（新規分だけ登録するため）")
                .isEqualTo(outboxAfterFirst);
        assertThat(reload().getUnconfirmedCount())
                .as("AC-23: unconfirmed_countも二重加算されない")
                .isEqualTo(unconfirmedAfterFirst).isEqualTo(500);
    }

    private ConfirmableNotificationEntity reload() {
        return notificationRepository.findById(notificationId).orElseThrow();
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

    private long countOutbox() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE source_domain = 'CONFIRMABLE_NOTIFICATION' "
                        + "AND source_event_id = ?",
                Long.class, String.valueOf(notificationId));
        return c == null ? 0 : c;
    }
}
