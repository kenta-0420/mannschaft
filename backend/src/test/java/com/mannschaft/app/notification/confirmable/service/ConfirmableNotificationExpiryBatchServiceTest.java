package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ConfirmableNotificationExpiryBatchService} の単体テスト。
 * 期限切れバッチのステータス変更ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmableNotificationExpiryBatchService 単体テスト")
class ConfirmableNotificationExpiryBatchServiceTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;

    @InjectMocks
    private ConfirmableNotificationExpiryBatchService batchService;

    // ========================================
    // テスト用ヘルパー
    // ========================================

    private ConfirmableNotificationEntity createActiveNotificationWithDeadline(
            LocalDateTime deadlineAt) {
        return ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(10L)
                .title("期限付き確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .deadlineAt(deadlineAt)
                .totalRecipientCount(2)
                .build();
    }

    private ConfirmableNotificationEntity createActiveNotificationWithoutDeadline() {
        return ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(10L)
                .title("無期限確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .totalRecipientCount(2)
                .build();
    }

    // ========================================
    // runBatch
    // ========================================

    @Nested
    @DisplayName("runBatch")
    class RunBatch {

        @Test
        @DisplayName("runBatch_期限超過ACTIVE通知をEXPIREDに変更_deadlineAtが過去でstatusがACTIVEの通知がEXPIREDになる")
        void runBatch_期限超過ACTIVE通知をEXPIREDに変更_deadlineAtが過去でstatusがACTIVEの通知がEXPIREDになる() {
            // given
            LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
            ConfirmableNotificationEntity expiredNotification =
                    createActiveNotificationWithDeadline(pastDeadline);

            // findExpiredNotifications が期限切れ対象を返す
            given(notificationRepository.findExpiredNotifications(any(LocalDateTime.class)))
                    .willReturn(List.of(expiredNotification));
            given(notificationRepository.save(any())).willReturn(expiredNotification);

            // when
            batchService.runBatch();

            // then
            assertThat(expiredNotification.getStatus()).isEqualTo(ConfirmableNotificationStatus.EXPIRED);
            assertThat(expiredNotification.getExpiredAt()).isNotNull();

            ArgumentCaptor<ConfirmableNotificationEntity> captor =
                    ArgumentCaptor.forClass(ConfirmableNotificationEntity.class);
            verify(notificationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ConfirmableNotificationStatus.EXPIRED);
        }

        @Test
        @DisplayName("runBatch_期限なし通知はスキップ_deadlineAtがnullの通知はEXPIREDにしない")
        void runBatch_期限なし通知はスキップ_deadlineAtがnullの通知はEXPIREDにしない() {
            // given
            // findExpiredNotifications の SQL クエリは deadline_at IS NOT NULL AND deadline_at < :now
            // なので deadline_at が null の通知は返されない（空リスト）
            given(notificationRepository.findExpiredNotifications(any(LocalDateTime.class)))
                    .willReturn(List.of());

            // when
            batchService.runBatch();

            // then: save は呼ばれない
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("runBatch_複数通知を一括EXPIRED変更_対象が複数あれば全てEXPIREDになる")
        void runBatch_複数通知を一括EXPIRED変更_対象が複数あれば全てEXPIREDになる() {
            // given
            LocalDateTime pastDeadline = LocalDateTime.now().minusDays(1);
            ConfirmableNotificationEntity notification1 =
                    createActiveNotificationWithDeadline(pastDeadline);
            ConfirmableNotificationEntity notification2 =
                    createActiveNotificationWithDeadline(pastDeadline.minusDays(1));

            given(notificationRepository.findExpiredNotifications(any(LocalDateTime.class)))
                    .willReturn(List.of(notification1, notification2));
            given(notificationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            batchService.runBatch();

            // then: 2件ともEXPIREDに変更される
            assertThat(notification1.getStatus()).isEqualTo(ConfirmableNotificationStatus.EXPIRED);
            assertThat(notification2.getStatus()).isEqualTo(ConfirmableNotificationStatus.EXPIRED);
            verify(notificationRepository, times(2)).save(any());
        }
    }
}
