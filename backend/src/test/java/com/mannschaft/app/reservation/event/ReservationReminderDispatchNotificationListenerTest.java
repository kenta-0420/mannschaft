package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.reservation.service.ReservationReminderDispatchRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationReminderDispatchNotificationListener} の単体テスト（F03.4 ⑥ / Issue #2997 G3）。
 *
 * <p>配送の前に claim（取れなければ配送しない）／成功・deny は印（SENT）を保持／配送が例外なら release で戻す／
 * 戻しの失敗でも例外を外に出さない／claim 自体の失敗では配送しない、を検証する。実 DB での印の遷移
 * （publish〜claim 前 PENDING・claim 後 SENT 等）は {@code ReservationNotificationTransactionIT} で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationReminderDispatchNotificationListener 単体テスト")
class ReservationReminderDispatchNotificationListenerTest {

    private static final ReservationReminderDispatchEvent EVENT = new ReservationReminderDispatchEvent(
            1L, 100L, 300L, 400L, java.time.LocalDate.of(2026, 4, 2), java.time.LocalTime.of(10, 0), "整体60分コース");

    @Mock
    private NotificationDeliveryRunner deliveryRunner;
    @Mock
    private ReservationReminderDispatchRunner reminderRunner;
    @Mock
    private UserLocaleCache userLocaleCache;

    private final ReloadableResourceBundleMessageSource messageSource = realMessageSource();

    private ReservationReminderDispatchNotificationListener listener;

    private static ReloadableResourceBundleMessageSource realMessageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @BeforeEach
    void setUp() {
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        listener = new ReservationReminderDispatchNotificationListener(
                deliveryRunner, reminderRunner, userLocaleCache, messageSource, java.time.Clock.systemUTC());
    }

    @Test
    @DisplayName("正常系: claim してから配送する（予約者本人・RESERVATION_REMINDER・TEAM スコープ・actor なし）。印は戻さない")
    void claimしてから配送() {
        given(reminderRunner.claim(eq(1L), any())).willReturn(true);
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onReservationReminderDispatch(EVENT);

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        var inOrder = org.mockito.Mockito.inOrder(reminderRunner, deliveryRunner);
        inOrder.verify(reminderRunner).claim(eq(1L), any());
        inOrder.verify(deliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(400L);
        assertThat(request.notificationType()).isEqualTo("RESERVATION_REMINDER");
        assertThat(request.priority()).isEqualTo(NotificationPriority.NORMAL);
        assertThat(request.sourceType()).isEqualTo("RESERVATION");
        assertThat(request.sourceId()).isEqualTo(100L);
        assertThat(request.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(request.scopeId()).isEqualTo(300L);
        assertThat(request.actionUrl()).isEqualTo("/teams/300/reservations");
        assertThat(request.actorId()).isNull();
        assertThat(request.body()).contains("4月2日 10:00").contains("整体60分コース");
        verify(reminderRunner, never()).release(any(), any());
    }

    @Test
    @DisplayName("claim が取れない（他の配送が担当済み）なら配送しない")
    void claimが取れなければ何もしない() {
        given(reminderRunner.claim(eq(1L), any())).willReturn(false);

        listener.onReservationReminderDispatch(EVENT);

        verify(deliveryRunner, never()).sendOne(any());
        verify(reminderRunner, never()).release(any(), any());
    }

    @Test
    @DisplayName("claim 自体が例外なら配送せず、印も戻さない（PENDING のまま）")
    void claim例外なら配送しない() {
        given(reminderRunner.claim(eq(1L), any())).willThrow(new RuntimeException("模擬 claim 失敗"));

        listener.onReservationReminderDispatch(EVENT);

        verify(deliveryRunner, never()).sendOne(any());
        verify(reminderRunner, never()).release(any(), any());
    }

    @Test
    @DisplayName("deny（VISIBILITY_DENIED）は SENT を保持する（戻さない）")
    void denyは戻さない() {
        given(reminderRunner.claim(eq(1L), any())).willReturn(true);
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        listener.onReservationReminderDispatch(EVENT);

        verify(reminderRunner, never()).release(any(), any());
    }

    @Test
    @DisplayName("配送が例外なら claimedAt 照合で release して PENDING に戻す（次周回で再送）")
    void 例外ならreleaseする() {
        given(reminderRunner.claim(eq(1L), any())).willReturn(true);
        doThrow(new RuntimeException("模擬配送失敗")).when(deliveryRunner).sendOne(any());

        listener.onReservationReminderDispatch(EVENT);

        ArgumentCaptor<LocalDateTime> claimed = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> released = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reminderRunner).claim(eq(1L), claimed.capture());
        verify(reminderRunner).release(eq(1L), released.capture());
        assertThat(released.getValue()).as("自分の claim だけを戻す").isEqualTo(claimed.getValue());
        // 本番 DDL の sent_at は秒精度（V3.065）。小数秒が残ると保存時に切り捨てられ release の照合が外れる。
        assertThat(claimed.getValue().getNano()).as("claimedAt は DB 精度（秒）に揃える").isZero();
    }

    @Test
    @DisplayName("戻しに失敗しても例外を外に出さない（SENT が残り再送されない。ERROR ログのみ）")
    void 戻し失敗でも例外を出さない() {
        given(reminderRunner.claim(eq(1L), any())).willReturn(true);
        doThrow(new RuntimeException("模擬配送失敗")).when(deliveryRunner).sendOne(any());
        doThrow(new RuntimeException("模擬戻し失敗")).when(reminderRunner).release(eq(1L), any());

        listener.onReservationReminderDispatch(EVENT);

        verify(reminderRunner).release(eq(1L), any());
    }

    @Test
    @DisplayName("受信者がいない（userId null）なら何もしない")
    void 受信者なしは何もしない() {
        listener.onReservationReminderDispatch(new ReservationReminderDispatchEvent(
                1L, 100L, 300L, null, java.time.LocalDate.of(2026, 4, 2), java.time.LocalTime.of(10, 0), "枠"));

        verify(reminderRunner, never()).claim(any(), any());
        verify(deliveryRunner, never()).sendOne(any());
    }
}
