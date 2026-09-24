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
 * CMP-260920-1040 試練B: {@link ConfirmableFanoutChunkSink} のチャンク処理・件数一致（軍議第8版確定稿
 * §3.4・§4 AC-18・§10.1 AC-63）。
 *
 * <p>1,201 人（チャンク 500 を 3 回ぶん）を {@code processChunk} で 500/500/201 と分けて処理し、
 * {@code finish} を呼んだあとに受信者行・notifications・unconfirmed_count が
 * すべて 1,201 に一致することを実 DB（Testcontainers MySQL）で検証する。</p>
 *
 * <p>受信者の展開（{@code ConfirmableTargetsFanoutRecipientSource}）は本試練の担当外のため、
 * user_id の集合はテストが直接 {@link ConfirmableFanoutFixture} で投入し、
 * {@link ConfirmableFanoutChunkSink#processChunk} へ直接渡す（陣立て書の「宛先グループ・展開は
 * ConfirmableTargetsFanoutRecipientSource に固定し、ここでは触らない」指示に従う）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ConfirmableFanoutChunkSink チャンク処理・件数一致（AC-18・AC-63・試練B）")
class ConfirmableFanoutChunkSinkChunkProcessingIT extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-chunk";

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
    @DisplayName("AC-18/AC-63: 1,201人をチャンク500/500/201で処理→finishで受信者行・notifications・"
            + "unconfirmed_countが1,201に一致し、delivery_status=DELIVERED・status=ACTIVEになる")
    void chunk500x2Plus201MatchesTotalAfterFinish() {
        emailPrefix = EMAIL_PREFIX_BASE + "-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 1201, emailPrefix);
        assertThat(userIds).hasSize(1201);

        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-18 1201人チャンク処理")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.QUEUED)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        notificationId = notification.getId();

        UUID jobId = UUID.randomUUID();
        List<Long> chunk1 = userIds.subList(0, 500);
        List<Long> chunk2 = userIds.subList(500, 1000);
        List<Long> chunk3 = userIds.subList(1000, 1201);

        FanoutChunkSink.ChunkResult r1 = sink.processChunk(jobId, notificationId, chunk1);
        FanoutChunkSink.ChunkResult r2 = sink.processChunk(jobId, notificationId, chunk2);
        FanoutChunkSink.ChunkResult r3 = sink.processChunk(jobId, notificationId, chunk3);

        assertThat(r1.stopped()).as("チャンク1はキャンセル・期限切れなしで処理される").isFalse();
        assertThat(r2.stopped()).isFalse();
        assertThat(r3.stopped()).isFalse();
        assertThat(r1.addedCount()).isEqualTo(500);
        assertThat(r2.addedCount()).isEqualTo(500);
        assertThat(r3.addedCount()).isEqualTo(201);

        sink.finish(jobId, notificationId);

        Long recipientCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        assertThat(recipientCount).as("AC-18: 受信者行は1,201に一致する").isEqualTo(1201L);

        Long distinctRecipientCount = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM confirmable_notification_recipients "
                        + "WHERE confirmable_notification_id = ?", Long.class, notificationId);
        assertThat(distinctRecipientCount).as("AC-18: 受信者は重複なく1,201人").isEqualTo(1201L);

        Long notificationRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                Long.class, notificationId);
        assertThat(notificationRowCount).as("AC-18: notifications行も1,201に一致する").isEqualTo(1201L);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getTotalRecipientCount()).as("total_recipient_countは実際に作った受信者行の数").isEqualTo(1201);
        assertThat(after.getDeliveredCount()).as("delivered_countも1,201").isEqualTo(1201);
        assertThat(after.getDeliveryStatus()).as("全チャンク処理後はDELIVERED")
                .isEqualTo(ConfirmableNotificationDeliveryStatus.DELIVERED);
        assertThat(after.getStatus()).as("誰も確認していないのでACTIVEのまま")
                .isEqualTo(ConfirmableNotificationStatus.ACTIVE);

        // AC-63: unconfirmed_count は「除外されておらず未確認の行」を数えた値と一致する。
        Long unconfirmedActual = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients "
                        + "WHERE confirmable_notification_id = ? AND is_confirmed = 0 AND excluded_at IS NULL",
                Long.class, notificationId);
        assertThat(after.getUnconfirmedCount())
                .as("AC-63: unconfirmed_countは受信者表の実測未確認数と一致する")
                .isEqualTo(unconfirmedActual.intValue())
                .isEqualTo(1201);
    }
}
