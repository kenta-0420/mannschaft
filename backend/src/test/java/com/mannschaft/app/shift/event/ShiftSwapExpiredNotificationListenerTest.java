package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftSwapExpiredNotificationListener} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>第1群で摘出された欠陥を再発させないことを固定する。</p>
 * <ul>
 *   <li>受信者ごとの隔離: 1 人ぶんが例外でも他の受信者へは送られる</li>
 *   <li>deny（{@code sendOne} が {@code null}）と例外を区別し、どちらでも配送ループを止めない</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftSwapExpiredNotificationListener 単体テスト")
class ShiftSwapExpiredNotificationListenerTest {

    @Mock private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private ShiftSwapExpiredNotificationListener listener;

    @BeforeEach
    void stubI18n() {
        Mockito.lenient().when(userLocaleCache.getLocales(anyList()))
                .thenReturn(Map.of(100L, "ja", 200L, "ja"));
        Mockito.lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @Test
    @DisplayName("受信者ごとに 1 件単位の配送 Runner が呼ばれる")
    void 受信者ごとに配送される() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));

        listener.onShiftSwapExpiredNotification(new ShiftSwapExpiredNotificationEvent(7L, List.of(100L, 200L)));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(2)).sendOne(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(100L, 200L);
        assertThat(captor.getAllValues())
                .allMatch(r -> "SHIFT_SWAP_REQUEST".equals(r.sourceType()) && Long.valueOf(7L).equals(r.sourceId()));
    }

    @Test
    @DisplayName("1人ぶんが例外でも他の受信者への配送は続行される（巻き添えにしない）")
    void 一人の例外で他が巻き添えにならない() {
        given(notificationDeliveryRunner.sendOne(any()))
                .willThrow(new RuntimeException("模擬DB例外"))
                .willReturn(Mockito.mock(NotificationEntity.class));

        assertThatCode(() -> listener.onShiftSwapExpiredNotification(
                new ShiftSwapExpiredNotificationEvent(7L, List.of(100L, 200L))))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("visibility deny（null 復帰）でも例外にせず、後続受信者へ送り続ける")
    void denyでもループを止めない() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(null);

        assertThatCode(() -> listener.onShiftSwapExpiredNotification(
                new ShiftSwapExpiredNotificationEvent(7L, List.of(100L, 200L))))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("locale の一括解決が失敗しても既定 locale で配送を続行する")
    void locale解決失敗でも配送する() {
        given(userLocaleCache.getLocales(anyList())).willThrow(new RuntimeException("模擬キャッシュ障害"));
        given(notificationDeliveryRunner.sendOne(any())).willReturn(Mockito.mock(NotificationEntity.class));

        listener.onShiftSwapExpiredNotification(new ShiftSwapExpiredNotificationEvent(7L, List.of(100L, 200L)));

        verify(notificationDeliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("受信者が空なら配送 Runner を呼ばない")
    void 受信者が空なら何もしない() {
        listener.onShiftSwapExpiredNotification(new ShiftSwapExpiredNotificationEvent(7L, List.of()));

        Mockito.verifyNoInteractions(notificationDeliveryRunner);
    }
}
