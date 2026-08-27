package com.mannschaft.app.quickmemo.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link QuickMemoReminderNotificationListener} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>deny（{@code sendOne} が {@code null}）と例外を区別し、どちらでもバッチ側へ例外を伝播させないこと、
 * プライバシー保護（件数のみで本文を作る）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickMemoReminderNotificationListener 単体テスト")
class QuickMemoReminderNotificationListenerTest {

    @Mock private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private QuickMemoReminderNotificationListener listener;

    @BeforeEach
    void stubI18n() {
        Mockito.lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        Mockito.lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    @DisplayName("PERSONAL スコープ・件数のみの本文で 1 件単位の配送 Runner が呼ばれる")
    void 集約通知が配送される() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));

        listener.onQuickMemoReminderNotification(new QuickMemoReminderNotificationEvent(100L, 3));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(100L);
        assertThat(request.notificationType()).isEqualTo("QUICK_MEMO_REMINDER");
        assertThat(request.scopeType()).isEqualTo(NotificationScopeType.PERSONAL);
        assertThat(request.scopeId()).isEqualTo(100L);
        assertThat(request.sourceType()).isEqualTo("QUICK_MEMO");
        assertThat(request.sourceId()).as("集約通知なので特定メモを指さない").isNull();
        assertThat(request.body()).contains("3").doesNotContain("メモのタイトル");
    }

    @Test
    @DisplayName("配送が例外でもバッチ側へ伝播させない（記録は既にコミット済み）")
    void 例外を伝播させない() {
        given(notificationDeliveryRunner.sendOne(any())).willThrow(new RuntimeException("模擬DB例外"));

        assertThatCode(() -> listener.onQuickMemoReminderNotification(
                new QuickMemoReminderNotificationEvent(100L, 1))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("visibility deny（null 復帰）でも例外にしない")
    void denyでも例外にしない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(null);

        assertThatCode(() -> listener.onQuickMemoReminderNotification(
                new QuickMemoReminderNotificationEvent(100L, 1))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("locale 解決に失敗しても既定 locale で配送する")
    void locale解決失敗でも配送する() {
        given(userLocaleCache.getLocale(anyLong())).willThrow(new RuntimeException("模擬キャッシュ障害"));
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));

        listener.onQuickMemoReminderNotification(new QuickMemoReminderNotificationEvent(100L, 1));

        verify(notificationDeliveryRunner).sendOne(any());
    }

    @Test
    @DisplayName("件数 0 なら空通知を送らない")
    void 件数ゼロなら送らない() {
        listener.onQuickMemoReminderNotification(new QuickMemoReminderNotificationEvent(100L, 0));

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }
}
