package com.mannschaft.app.safetycheck;



import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.safetycheck.event.SafetyCheckReminderNotificationEvent;
import com.mannschaft.app.safetycheck.event.SafetyCheckReminderNotificationListener;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue #2834 / CMP-056 第1群ロットA — {@link SafetyCheckReminderNotificationListener} のユニットテスト。
 *
 * <p>単一受信者の配送リスナーの番人。組み立て（{@code getLocale} / {@code getMessage}）も配送
 * （{@code sendOne}）もリスナー内 {@code try/catch} の内側にあり、いずれの失敗も呼び出し元
 * （{@code AFTER_COMMIT} リスナーの実行スレッド）へ伝播しないことを検証する（AC-1 の非TX側の裏付け。
 * 実DBでのコミット検証は {@code ContactInviteUsedNotificationTransactionIT} 側で行う）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafetyCheckReminderNotificationListener ユニットテスト")
class SafetyCheckReminderNotificationListenerTest {

    private static final Long SAFETY_CHECK_ID = 1L;
    private static final Long RECIPIENT_ID = 100L;
    private static final Long SCOPE_ID = 10L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private SafetyCheckReminderNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new SafetyCheckReminderNotificationListener(
                notificationDeliveryRunner, userLocaleCache, messageSource);
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private SafetyCheckReminderNotificationEvent event() {
        return new SafetyCheckReminderNotificationEvent(SAFETY_CHECK_ID, RECIPIENT_ID, "TEAM", SCOPE_ID);
    }

    @Test
    @DisplayName("リマインド通知が1件、Runner経由（1件=1独立トランザクション）で送られる")
    void リマインド通知が1件送られる() {
        given(notificationDeliveryRunner.sendOne(any()))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onSafetyCheckReminderNotification(event());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(RECIPIENT_ID);
        assertThat(request.notificationType()).isEqualTo("SAFETY_CHECK_REMINDER");
        assertThat(request.priority()).isEqualTo(NotificationPriority.URGENT);
        // 是正前（notificationHelper.notify 直接呼び出し）と同じ sourceType / sourceId / 遷移先を保つ。
        assertThat(request.sourceType()).isEqualTo("SAFETY_CHECK");
        assertThat(request.sourceId()).isEqualTo(SAFETY_CHECK_ID);
        assertThat(request.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(request.scopeId()).isEqualTo(SCOPE_ID);
        assertThat(request.actionUrl()).isEqualTo("/safety-checks/" + SAFETY_CHECK_ID);
    }

    @Test
    @DisplayName("AC-4: visibility deny（sendOne が null 復帰）は例外扱いせず、リスナーは正常終了する")
    void denyは例外扱いされない() {
        // NotificationService は deny 時に例外を投げず null を返す（成功ではない）。
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onSafetyCheckReminderNotification(event()))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner).sendOne(any());
    }

    @Test
    @DisplayName("AC-1(非TX側): 配送のDB例外は呼び出し元へ伝播しない（業務TXを巻き込まない）")
    void 配送例外は呼び出し元へ伝播しない() {
        willThrow(new RuntimeException("模擬通知配送失敗"))
                .given(notificationDeliveryRunner).sendOne(any());

        assertThatCode(() -> listener.onSafetyCheckReminderNotification(event()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("組み立て中（getLocale）の例外も呼び出し元へ伝播せず、配送は試みられない")
    void 組み立て例外は呼び出し元へ伝播しない() {
        given(userLocaleCache.getLocale(RECIPIENT_ID)).willThrow(new RuntimeException("locale解決失敗"));

        assertThatCode(() -> listener.onSafetyCheckReminderNotification(event()))
                .doesNotThrowAnyException();

        verifyNoInteractions(notificationDeliveryRunner);
    }
}
