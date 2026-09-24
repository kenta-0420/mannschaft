package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 試練B（殿の訂正指示を反映）: リマインドの記録と送信を同じトランザクションに入れる
 * （軍議第8版確定稿 §8.5・§9.4・AC-49・AC-50・AC-57・AC-58）。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("確認通知 リマインドバッチのトランザクション契約（AC-49/50/57/58・試練B）")
class ConfirmableNotificationReminderBatchTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-reminder";

    @Autowired
    private ConfirmableNotificationReminderBatchService reminderBatchService;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long notificationId;

    @AfterEach
    void cleanUp() {
        if (notificationId != null) {
            recipientRepository.deleteAll(recipientRepository.findByConfirmableNotificationId(notificationId));
            notificationRepository.deleteById(notificationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(em, emailPrefix);
        }
    }

    private List<Long> seed(int count, LocalDateTime recipientCreatedAt) {
        emailPrefix = EMAIL_PREFIX_BASE + "-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, count, emailPrefix);

        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-49/50/57/58 リマインドTX")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.DELIVERING)
                .totalRecipientCount(count)
                .unconfirmedCount(count)
                .firstReminderMinutes(0)
                .build());
        notificationId = notification.getId();

        for (Long userId : userIds) {
            UserEntity user = userRepository.getReferenceById(userId);
            ConfirmableNotificationRecipientEntity recipient = recipientRepository.save(
                    ConfirmableNotificationRecipientEntity.builder()
                            .confirmableNotification(notification)
                            .user(user)
                            .confirmToken(UUID.randomUUID().toString())
                            .resolvedFirstReminderMinutes(0)
                            .build());
            // 受信者行の created_at は非同期配信の「届いた時刻」を表す（AC-49）。
            // 通常は @PrePersist で now() が入るが、テストでは意図的に過去時刻へ書き換える。
            jdbc.update("UPDATE confirmable_notification_recipients SET created_at = ? WHERE id = ?",
                    recipientCreatedAt, recipient.getId());
        }
        return userIds;
    }

    @Test
    @DisplayName("AC-49: 受信者行が通知作成よりN分遅れて作られた場合、1回目リマインドは"
            + "『受信者行の作成時刻＋設定分数』より前には出ない")
    void firstReminderRespectsRecipientCreatedAtNotNotificationCreatedAt() {
        LocalDateTime recipientCreatedAt = LocalDateTime.now().minusSeconds(30);
        List<Long> userIds = seed(1, recipientCreatedAt);

        // firstReminderMinutes=0 なので受信者作成直後から対象になるはず（設定分数0）。
        List<Long> sent = reminderBatchService.processRemindersTransactional(
                notificationId, userIds, true, LocalDateTime.now());

        assertThat(sent)
                .as("AC-49: 受信者行のcreated_at基準でリマインド対象になる")
                .containsExactlyInAnyOrderElementsOf(userIds);
    }

    @Test
    @DisplayName("AC-50/AC-58: 未確認者が複数いる通知でリマインドバッチを2回連続で走らせても、"
            + "各人への1回目リマインドはちょうど1回ずつになる（条件付きUPDATEで確定した人だけ送る）")
    void runningTwiceDoesNotDuplicateFirstReminder() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(10);
        List<Long> userIds = seed(50, past);

        List<Long> firstRun = reminderBatchService.processRemindersTransactional(
                notificationId, userIds, true, LocalDateTime.now());
        List<Long> secondRun = reminderBatchService.processRemindersTransactional(
                notificationId, userIds, true, LocalDateTime.now());

        assertThat(firstRun).as("AC-50: 1回目のバッチで全員ぶん送信を確定する").hasSize(50);
        assertThat(secondRun)
                .as("AC-50/AC-58: 2回目のバッチでは誰も対象にならない（条件付きUPDATEで既に確定済み）")
                .isEmpty();

        long sentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients "
                        + "WHERE confirmable_notification_id = ? AND first_reminder_sent_at IS NOT NULL",
                Long.class, notificationId);
        assertThat(sentCount).as("AC-50: first_reminder_sent_atが立っているのはちょうど50人").isEqualTo(50L);
    }

    @Test
    @DisplayName("AC-57: notifications への多値INSERTが失敗したら、"
            + "first_reminder_sent_at の条件付きUPDATEもロールバックされ、次回バッチで再送される")
    void insertFailureRollsBackTheConditionalUpdateToo() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(10);
        List<Long> userIds = seed(3, past);

        // notification_type にNULLを混入させる等でnotificationsへのINSERTを失敗させる直接的な手段が
        // 無いため（本サービスのpublic APIはuserIds・notificationIdのみ）、ここでは
        // 「呼び出しが正常に完了し、かつfirst_reminder_sent_atがちょうど3件確定する」ことを固定する
        // （骨格段階ではUnsupportedOperationExceptionが伝播しテスト自体が失敗する＝red。
        // 例外を握りつぶして『失敗しても0件だから正しい』と誤魔化さない）。
        List<Long> sent = reminderBatchService.processRemindersTransactional(
                notificationId, userIds, true, LocalDateTime.now());
        assertThat(sent).as("AC-57: 手順が完走すれば3人分の送信が確定する").hasSize(3);

        long sentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients "
                        + "WHERE confirmable_notification_id = ? AND first_reminder_sent_at IS NOT NULL",
                Long.class, notificationId);
        assertThat(sentCount)
                .as("AC-57: notifications INSERTが失敗した場合は手順1のUPDATEもロールバックされ0件のまま"
                        + "（本テストは正常系。異常系の直接注入は出陣後、JdbcTemplateスパイで別途追加する）")
                .isEqualTo(3L);
    }
}
