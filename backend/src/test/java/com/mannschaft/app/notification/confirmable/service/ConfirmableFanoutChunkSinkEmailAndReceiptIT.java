package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
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
 * CMP-260920-1040 試練B（殿の訂正指示を反映）: メールアドレス欠落時のoutbox扱いと、
 * 非同期で届いた受信者の受信・確認までの一気通貫（軍議第8版確定稿 §8.4・AC-29・AC-48）。
 *
 * <p><b>AC-21 について</b>: 軍議第8版確定稿 §9.1 により AC-21 は AC-54 に置き換えられている
 * （「受け付けてからワーカーが処理するまでの間に受信者が0件になった場合」の期待値が、
 * 例外にしないという記述から「delivery_status=DELIVERED・total_recipient_count=0・
 * statusはACTIVEのまま（COMPLETEDにしない）」という具体的な終局状態の記述に強化された）。
 * したがって本ファイルでは AC-21 を独立して書かず、
 * {@link ConfirmableFanoutChunkSinkCancelExpiryIT#finishWithZeroRecipientsStaysActiveNotCompleted()}
 * （AC-54）がその内容を代替する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ConfirmableFanoutChunkSink メールoutboxの欠落処理と受信〜確認の一気通貫（AC-29/48・試練B）")
class ConfirmableFanoutChunkSinkEmailAndReceiptIT extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-email";

    @Autowired
    private ConfirmableFanoutChunkSink sink;

    @Autowired
    private ConfirmableNotificationConfirmService confirmService;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;

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
            jdbc.update("DELETE FROM email_outbox WHERE source_domain = 'CONFIRMABLE_NOTIFICATION' "
                    + "AND source_event_id = ?", String.valueOf(notificationId));
            jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", notificationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(em, emailPrefix);
        }
    }

    @Test
    @DisplayName("AC-48: メールアドレスが空文字の受信者はoutboxに登録されないが、"
            + "受信者行と課金（受信者数としてのカウント）は作られる")
    void recipientWithoutEmailIsSkippedInOutboxButStillCounted() {
        emailPrefix = EMAIL_PREFIX_BASE + "-48-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 5, emailPrefix);

        // 1人だけメールアドレスを空にする（「メールアドレスが無い受信者」の代替表現。
        // usersテーブルのemail列はNOT NULLのため、空文字をアドレス未解決の代理として使う）。
        Long noEmailUserId = userIds.get(0);
        jdbc.update("UPDATE users SET email = '' WHERE id = ?", noEmailUserId);

        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-48 メールアドレス欠落")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.QUEUED)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        notificationId = notification.getId();

        sink.processChunk(UUID.randomUUID(), notificationId, userIds);

        long recipients = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        long outboxRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE source_domain = 'CONFIRMABLE_NOTIFICATION' "
                        + "AND source_event_id = ?", Long.class, String.valueOf(notificationId));

        assertThat(recipients)
                .as("AC-48: メールアドレスが無い受信者も受信者行としては作られる（5人全員）")
                .isEqualTo(5L);
        assertThat(outboxRows)
                .as("AC-48: outboxはメールアドレスが解決できた4人ぶんだけ（無い1人は登録しない）")
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("AC-29: 非同期で届いた確認通知はnotifications行として作られ、"
            + "受信者が確認すると送信側の受信者行にも反映される")
    void asyncDeliveredNotificationCanBeConfirmedAndReflected() {
        emailPrefix = EMAIL_PREFIX_BASE + "-29-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 1, emailPrefix);
        Long recipientUserId = userIds.get(0);

        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-29 受信〜確認の一気通貫")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.QUEUED)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        notificationId = notification.getId();

        sink.processChunk(UUID.randomUUID(), notificationId, userIds);
        sink.finish(UUID.randomUUID(), notificationId);

        long notificationsRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' "
                        + "AND source_id = ? AND user_id = ?", Long.class, notificationId, recipientUserId);
        assertThat(notificationsRowCount)
                .as("AC-29: 受信者の/notificationsに出る行が作られている")
                .isEqualTo(1L);

        // 受信者が「確認する」を押す。
        confirmService.confirm(notificationId, recipientUserId);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getUnconfirmedCount())
                .as("AC-29: 確認すると送信者の確認状況（unconfirmed_count）に反映される")
                .isZero();
        assertThat(after.getStatus())
                .as("AC-29: 唯一の受信者が確認済みになればCOMPLETEDになる")
                .isEqualTo(ConfirmableNotificationStatus.COMPLETED);
    }
}
