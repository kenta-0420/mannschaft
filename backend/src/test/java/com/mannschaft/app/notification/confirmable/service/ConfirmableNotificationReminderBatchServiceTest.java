package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationReminderRunner.SenderAlertContext;
import org.springframework.context.MessageSource;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationReminderRunner.ReminderCommit;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ConfirmableNotificationReminderBatchService} のオーケストレータ単体テスト（CMP-260925-0905）。
 * 対象者の絞り込み（N+1 回避）、配信プール拒否時の挙動、チャンク失敗時の1人ずつ再試行を固定する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableNotificationReminderBatchService オーケストレータ単体テスト")
class ConfirmableNotificationReminderBatchServiceTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;
    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;
    @Mock
    private ConfirmableNotificationReminderRunner reminderRunner;
    @Mock
    private NotificationDispatchService dispatchService;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ConfirmableNotificationReminderBatchService service;

    private ConfirmableNotificationRecipientEntity recipient(long userId, boolean first, boolean second) {
        ConfirmableNotificationRecipientEntity r = mock(ConfirmableNotificationRecipientEntity.class);
        given(r.getRecipientUserId()).willReturn(userId);
        given(r.needsFirstReminder(any(LocalDateTime.class))).willReturn(first);
        given(r.needsSecondReminder(any(LocalDateTime.class))).willReturn(second);
        return r;
    }

    private void givenActiveNotification(long id) {
        ConfirmableNotificationEntity n = mock(ConfirmableNotificationEntity.class);
        given(n.getId()).willReturn(id);
        given(notificationRepository.findByStatus(ConfirmableNotificationStatus.ACTIVE)).willReturn(List.of(n));
        given(reminderRunner.evaluateSenderAlert(id)).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("対象外の受信者（時刻未到来・送信済み）は Runner に渡さない（毎分の独立TX・個別SELECTを避ける）")
    void 対象外の受信者はRunnerに渡さない() {
        givenActiveNotification(100L);
        var r1 = recipient(1L, true, false);
        var r2 = recipient(2L, false, false);
        var r3 = recipient(3L, false, true);
        given(recipientRepository.findActiveUnconfirmedByNotificationId(100L)).willReturn(List.of(r1, r2, r3));
        given(reminderRunner.commitReminders(eq(100L), anyList(), anyBoolean(), any()))
                .willReturn(ReminderCommit.empty());

        service.runBatch();

        verify(reminderRunner).commitReminders(eq(100L), eq(List.of(1L)), eq(true), any());
        verify(reminderRunner).commitReminders(eq(100L), eq(List.of(3L)), eq(false), any());
        verify(reminderRunner, never()).commitReminders(eq(100L), eq(List.of(2L)), anyBoolean(), any());
    }

    @Test
    @DisplayName("リアルタイム配信の投入が拒否されても（プール飽和）、例外は伝播せず後続の受信者の処理を続ける")
    void 配信プール拒否でも後続を続ける() {
        givenActiveNotification(100L);
        var r1 = recipient(1L, true, false);
        var r3 = recipient(3L, false, true);
        given(recipientRepository.findActiveUnconfirmedByNotificationId(100L)).willReturn(List.of(r1, r3));
        NotificationEntity row = NotificationEntity.builder().id(10L).userId(1L).build();
        given(reminderRunner.commitReminders(eq(100L), eq(List.of(1L)), eq(true), any()))
                .willReturn(new ReminderCommit(List.of(1L), List.of(row)));
        given(reminderRunner.commitReminders(eq(100L), eq(List.of(3L)), eq(false), any()))
                .willReturn(new ReminderCommit(List.of(3L), List.of(row)));
        org.mockito.Mockito.doThrow(new RejectedExecutionException("pool saturated"))
                .when(dispatchService).dispatchBatch(anyList());

        assertThatCode(() -> service.runBatch()).doesNotThrowAnyException();

        // 通知行は Runner の TX で作成済み（commitReminders が返した）。2件目も処理されている。
        verify(reminderRunner).commitReminders(eq(100L), eq(List.of(3L)), eq(false), any());
    }

    @Test
    @DisplayName("チャンクの確定が失敗したら1人ずつに分けて再試行し、失敗した人以外は確定する")
    void チャンク失敗は一人ずつ再試行する() {
        givenActiveNotification(100L);
        var r1 = recipient(1L, true, false);
        var r2 = recipient(2L, true, false);
        var r3 = recipient(3L, true, false);
        given(recipientRepository.findActiveUnconfirmedByNotificationId(100L)).willReturn(List.of(r1, r2, r3));
        given(reminderRunner.commitReminders(eq(100L), eq(List.of(1L, 2L, 3L)), eq(true), any()))
                .willThrow(new IllegalStateException("db error"));
        given(reminderRunner.commitReminders(eq(100L), eq(List.of(1L)), eq(true), any()))
                .willReturn(new ReminderCommit(List.of(1L), List.of()));
        given(reminderRunner.commitReminders(eq(100L), eq(List.of(2L)), eq(true), any()))
                .willThrow(new IllegalStateException("db error"));
        given(reminderRunner.commitReminders(eq(100L), eq(List.of(3L)), eq(true), any()))
                .willReturn(new ReminderCommit(List.of(3L), List.of()));

        assertThatCode(() -> service.runBatch()).doesNotThrowAnyException();

        verify(reminderRunner).commitReminders(eq(100L), eq(List.of(1L)), eq(true), any());
        verify(reminderRunner).commitReminders(eq(100L), eq(List.of(3L)), eq(true), any());
    }

    @Test
    @DisplayName("Issue #2715 ロットB: 送信者 locale が en の場合、アラート件名・本文が英語で組み立てられプレースホルダが残らない")
    void 送信者ロケールがenなら英語件名本文になる() {
        var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "messageSource", realMessageSource);
        given(userLocaleCache.getLocale(7L)).willReturn("en");

        givenActiveNotification(100L);
        given(recipientRepository.findActiveUnconfirmedByNotificationId(100L)).willReturn(List.of());
        given(reminderRunner.evaluateSenderAlert(100L)).willReturn(Optional.of(new SenderAlertContext(
                7L, 100L, "テスト確認通知", 50, 80, NotificationScopeType.TEAM, 10L, null)));

        service.runBatch();

        org.mockito.ArgumentCaptor<String> title = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notify(
                eq(7L), eq("CONFIRMABLE_NOTIFICATION_SENDER_ALERT"), any(NotificationPriority.class),
                title.capture(), body.capture(), eq("CONFIRMABLE_NOTIFICATION"), eq(100L),
                eq(NotificationScopeType.TEAM), eq(10L), any(), any());
        assertThat(title.getValue()).isEqualTo("Confirmation notification alert");
        assertThat(body.getValue()).doesNotContain("{0}").doesNotContain("{1}").doesNotContain("{2}")
                .contains("50").contains("テスト確認通知");
    }
}
