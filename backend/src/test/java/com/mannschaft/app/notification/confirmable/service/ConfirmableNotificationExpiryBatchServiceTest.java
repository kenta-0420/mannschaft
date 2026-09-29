package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfirmableNotificationExpiryBatchService} の試練。
 *
 * <p>CMP-260920-1040 是正6: 軍議第8版確定稿 §11.1 で {@code runBatch} は
 * 「ID だけを抽出（{@code findExpiredIds}）→ 1件ごとに独立トランザクション（{@code TransactionTemplate}・
 * {@code REQUIRES_NEW}）で {@code findByIdForUpdate} してロック・再判定」という形に変わった。
 * {@code TransactionTemplate} は Mockito のモックでは実行できない（実トランザクションマネージャが要る）ため、
 * 本テストは Testcontainers の実 DB を使う IT として書き直す（是正: 旧 {@code findExpiredNotifications}
 * 全件ロード前提の Mockito 単体テストから昇格。並行競合系の AC-65a/b/c・AC-67・AC-68 は
 * {@link ConfirmableNotificationLockOrderingConcurrentIT} が別途担当する）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ConfirmableNotificationExpiryBatchService 試練")
class ConfirmableNotificationExpiryBatchServiceTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ConfirmableNotificationExpiryBatchService batchService;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            notificationRepository.deleteById(id);
        }
        createdIds.clear();
    }

    private Long createNotification(LocalDateTime deadlineAt, ConfirmableNotificationStatus status) {
        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(10L)
                .title("期限付き確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(status)
                .deadlineAt(deadlineAt)
                .totalRecipientCount(2)
                .unconfirmedCount(2)
                .build());
        createdIds.add(notification.getId());
        return notification.getId();
    }

    @Nested
    @DisplayName("runBatch")
    class RunBatch {

        @Test
        @DisplayName("runBatch_期限超過ACTIVE通知をEXPIREDに変更_deadlineAtが過去でstatusがACTIVEの通知がEXPIREDになる")
        void runBatch_期限超過ACTIVE通知をEXPIREDに変更_deadlineAtが過去でstatusがACTIVEの通知がEXPIREDになる() {
            Long id = createNotification(LocalDateTime.now().minusDays(1), ConfirmableNotificationStatus.ACTIVE);

            batchService.runBatch();

            ConfirmableNotificationEntity after = notificationRepository.findById(id).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ConfirmableNotificationStatus.EXPIRED);
            assertThat(after.getExpiredAt()).isNotNull();
        }

        @Test
        @DisplayName("runBatch_期限なし通知はスキップ_deadlineAtがnullの通知はEXPIREDにしない")
        void runBatch_期限なし通知はスキップ_deadlineAtがnullの通知はEXPIREDにしない() {
            Long id = createNotification(null, ConfirmableNotificationStatus.ACTIVE);

            batchService.runBatch();

            ConfirmableNotificationEntity after = notificationRepository.findById(id).orElseThrow();
            assertThat(after.getStatus())
                    .as("deadline_at IS NOT NULL AND deadline_at < now の条件に合致しないためEXPIREDにならない")
                    .isEqualTo(ConfirmableNotificationStatus.ACTIVE);
        }

        @Test
        @DisplayName("runBatch_複数通知を一括EXPIRED変更_対象が複数あれば全てEXPIREDになる")
        void runBatch_複数通知を一括EXPIRED変更_対象が複数あれば全てEXPIREDになる() {
            LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
            Long id1 = createNotification(pastDeadline, ConfirmableNotificationStatus.ACTIVE);
            Long id2 = createNotification(pastDeadline.minusDays(1), ConfirmableNotificationStatus.ACTIVE);

            batchService.runBatch();

            assertThat(notificationRepository.findById(id1).orElseThrow().getStatus())
                    .isEqualTo(ConfirmableNotificationStatus.EXPIRED);
            assertThat(notificationRepository.findById(id2).orElseThrow().getStatus())
                    .isEqualTo(ConfirmableNotificationStatus.EXPIRED);
        }

        @Test
        @DisplayName("runBatch_ACTIVE以外はEXPIREDにしない_期限が過去でもCANCELLED等は対象外")
        void runBatch_ACTIVE以外はEXPIREDにしない() {
            Long id = createNotification(LocalDateTime.now().minusDays(1), ConfirmableNotificationStatus.CANCELLED);

            batchService.runBatch();

            assertThat(notificationRepository.findById(id).orElseThrow().getStatus())
                    .as("findExpiredIdsはstatus='ACTIVE'条件のため対象に含まれない")
                    .isEqualTo(ConfirmableNotificationStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("expireOneWithLock")
    class ExpireOneWithLock {

        @Test
        @DisplayName("expireOneWithLock_ACTIVEかつ期限超過ならtrueを返しEXPIREDにする")
        void expireOneWithLock_正常系() {
            Long id = createNotification(LocalDateTime.now().minusMinutes(1), ConfirmableNotificationStatus.ACTIVE);

            boolean expired = batchService.expireOneWithLock(id, LocalDateTime.now());

            assertThat(expired).isTrue();
            assertThat(notificationRepository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(ConfirmableNotificationStatus.EXPIRED);
        }

        @Test
        @DisplayName("expireOneWithLock_存在しないIDならfalseを返す")
        void expireOneWithLock_存在しないID() {
            boolean expired = batchService.expireOneWithLock(999_999_999L, LocalDateTime.now());

            assertThat(expired).isFalse();
        }
    }
}
