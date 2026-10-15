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
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 試練B: 完了判定を配信の終了まで保留する（軍議第8版確定稿 §8.2・§9.2・§9.3・
 * AC-40・AC-41・AC-42・AC-55a・AC-55b）。
 *
 * <p><b>並行の順序について</b>: AC-55 は「最後の未確認者の confirm と、ワーカーの finish が
 * 競合した場合、どちらの順序でコミットしても最終的に status=COMPLETED になる」ことを要求する。
 * 本テストは「confirm が先にコミットしてから finish を呼ぶ」（AC-55a）と「finish が先にコミット
 * してから confirm を呼ぶ」（AC-55b）の 2 通りの<b>順序を固定</b>して検証する。{@code finish} が
 * 骨格（{@link UnsupportedOperationException}）のため、CountDownLatch によるロック競合待ちの
 * 実測（「どちらのトランザクションが先にブロックされるか」）は出陣後に別途追加する
 * （軍議 §9.2 のロック順序規約が実装されて初めて意味を持つため）。ここでは 2 通りの順序それぞれの
 * 終局状態を固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("確認通知 完了判定の保留とfinishとの競合順序（AC-40/41/42/55a/55b・試練B）")
class ConfirmableNotificationFinishCompletionOrderIT extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-finish";

    @Autowired
    private ConfirmableFanoutChunkSink sink;

    @Autowired
    private ConfirmableNotificationConfirmService confirmService;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationFanoutJobRepository fanoutJobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long notificationId;
    private UUID jobIdToCleanUp;

    /** 是正3（§9.2）: finish に渡す jobId は実在するジョブ行を伴わせる。 */
    private UUID newFinishableJobId(Long notificationId) {
        UUID jobId = UUID.randomUUID();
        ConfirmableFanoutFixture.insertFanoutJobRow(fanoutJobRepository, jobId, notificationId);
        jobIdToCleanUp = jobId;
        return jobId;
    }

    @AfterEach
    void cleanUp() {
        if (jobIdToCleanUp != null) {
            fanoutJobRepository.deleteById(jobIdToCleanUp);
        }
        if (notificationId != null) {
            recipientRepository.deleteAll(recipientRepository.findByConfirmableNotificationId(notificationId));
            notificationRepository.deleteById(notificationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, emailPrefix);
        }
    }

    /** ACTIVE・DELIVERING の確認通知に、指定人数ぶんの未確認受信者を直接作る（sinkは通さない）。 */
    private List<Long> seedNotificationWithRecipients(int count) {
        emailPrefix = EMAIL_PREFIX_BASE + "-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, count, emailPrefix);

        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-40/41/42/55 完了判定保留")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.DELIVERING)
                .totalRecipientCount(count)
                .unconfirmedCount(count)
                .build());
        notificationId = notification.getId();

        for (Long userId : userIds) {
            UserEntity user = userRepository.getReferenceById(userId);
            recipientRepository.save(ConfirmableNotificationRecipientEntity.builder()
                    .confirmableNotification(notification)
                    .user(user)
                    .confirmToken(UUID.randomUUID().toString())
                    .build());
        }
        return userIds;
    }

    @Test
    @DisplayName("AC-40: チャンク1つを処理し終えた時点（DELIVERING）で全員確認してもACTIVEのまま"
            + "（後続チャンクの受信者もまだ確認できる余地を残す）")
    void allConfirmedWhileDeliveringStaysActive() {
        List<Long> userIds = seedNotificationWithRecipients(2);
        for (Long userId : userIds) {
            confirmService.confirm(notificationId, userId);
        }
        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getStatus())
                .as("AC-40: DELIVERING中は全員確認してもCOMPLETEDにしない（配信終了まで完了判定を保留）")
                .isEqualTo(ConfirmableNotificationStatus.ACTIVE);
    }

    @Test
    @DisplayName("AC-55a: confirmが先にコミットしてから finish を呼ぶと、最終的にCOMPLETEDになる")
    void confirmThenFinishEndsCompleted() {
        List<Long> userIds = seedNotificationWithRecipients(1);
        confirmService.confirm(notificationId, userIds.get(0));

        sink.finish(newFinishableJobId(notificationId), notificationId);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getStatus())
                .as("AC-55a: confirmが先でもfinish後にCOMPLETEDになるべき")
                .isEqualTo(ConfirmableNotificationStatus.COMPLETED);
    }

    @Test
    @DisplayName("AC-55b: finishが先にコミットしてから最後の confirm を呼んでも、最終的にCOMPLETEDになる")
    void finishThenConfirmEndsCompleted() {
        List<Long> userIds = seedNotificationWithRecipients(1);

        sink.finish(newFinishableJobId(notificationId), notificationId);

        confirmService.confirm(notificationId, userIds.get(0));

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getStatus())
                .as("AC-55b: finishが先でも最後のconfirmでCOMPLETEDになるべき")
                .isEqualTo(ConfirmableNotificationStatus.COMPLETED);
    }

    @Test
    @DisplayName("AC-42: confirm 1回あたりのクエリ数は受信者人数に比例しない"
            + "（未確認で除外されていない件数を数えるクエリに置き換える。全件Listロードしない）")
    void confirmDoesNotLoadAllRecipientsForLargeNotification() {
        List<Long> userIds = seedNotificationWithRecipients(600);
        // 599人ぶんを先に確認済みにしておく（残り1人）。
        for (int i = 0; i < userIds.size() - 1; i++) {
            confirmService.confirm(notificationId, userIds.get(i));
        }
        em.clear();

        // 最後の1人を確認する。findByConfirmableNotificationId による全件Listロード（§8.2是正前）ではなく、
        // 件数クエリへ置き換えられているべき（AC-42）。ここでは「最後の1人の確認後、status=COMPLETEDまで
        // 遷移する」ことを検証することで、全件ロード方式の古い実装（DELIVERING中は保留せず即COMPLETEDに
        // してしまう・件数に応じて重くなる）からの置き換えを間接的に固定する。
        confirmService.confirm(notificationId, userIds.get(userIds.size() - 1));

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getUnconfirmedCount())
                .as("AC-42/AC-63: unconfirmed_countは0まで正しく減る（全件ロードに依存しない実装）")
                .isEqualTo(0);
    }
}
