package com.mannschaft.app.reservation.event;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EmergencyClosureNotificationListener} の単体テスト（Issue #2997 G3）。
 *
 * <p>受信者ごとに 1 件ずつ {@code sendOne} する／1 人の失敗が他に波及しない／deny は例外ではない／
 * 元データが無ければ配送しない、を検証する。実 DB でのコミット／ロールバック境界は
 * {@code ReservationNotificationTransactionIT} で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureNotificationListener 単体テスト")
class EmergencyClosureNotificationListenerTest {

    private static final Long CLOSURE_ID = 55L;

    @Mock
    private NotificationDeliveryRunner deliveryRunner;
    @Mock
    private EmergencyClosureRepository closureRepository;

    @InjectMocks
    private EmergencyClosureNotificationListener listener;

    private EmergencyClosureEntity closure() {
        EmergencyClosureEntity closure = EmergencyClosureEntity.builder()
                .teamId(7L).startDate(LocalDate.of(2026, 4, 8)).endDate(LocalDate.of(2026, 4, 8))
                .reason("先生体調不良").subject("臨時休業のお知らせ").messageBody("本日休業")
                .createdBy(9L).build();
        return closure;
    }

    @Test
    @DisplayName("正常系: 受信者ごとに URGENT の EMERGENCY_CLOSURE 通知を sendOne する（件名＝subject、本文＝reason、actor＝送信者）")
    void 受信者ごとに配送() {
        given(closureRepository.findById(CLOSURE_ID)).willReturn(Optional.of(closure()));
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onEmergencyClosureNotification(new EmergencyClosureNotificationEvent(CLOSURE_ID, List.of(1L, 2L)));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner, times(2)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(1L, 2L);
        NotificationDeliveryRequest first = captor.getAllValues().get(0);
        assertThat(first.notificationType()).isEqualTo("EMERGENCY_CLOSURE");
        assertThat(first.priority()).isEqualTo(NotificationPriority.URGENT);
        assertThat(first.title()).isEqualTo("臨時休業のお知らせ");
        assertThat(first.body()).isEqualTo("先生体調不良");
        assertThat(first.sourceType()).isEqualTo("EMERGENCY_CLOSURE");
        assertThat(first.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(first.scopeId()).isEqualTo(7L);
        assertThat(first.actorId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("AC-B2: 受信者の 1 人が失敗（例外）しても、他の受信者への配送は続く")
    void 一人の失敗は他に波及しない() {
        given(closureRepository.findById(CLOSURE_ID)).willReturn(Optional.of(closure()));
        doThrow(new RuntimeException("模擬配送失敗")).when(deliveryRunner)
                .sendOne(argThat(r -> r != null && r.recipientUserId() == 1L));
        given(deliveryRunner.sendOne(argThat(r -> r != null && r.recipientUserId() != 1L)))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onEmergencyClosureNotification(new EmergencyClosureNotificationEvent(CLOSURE_ID, List.of(1L, 2L, 3L)));

        verify(deliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("deny（VISIBILITY_DENIED）は例外ではなく、配送は続く")
    void denyは例外ではない() {
        given(closureRepository.findById(CLOSURE_ID)).willReturn(Optional.of(closure()));
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        listener.onEmergencyClosureNotification(new EmergencyClosureNotificationEvent(CLOSURE_ID, List.of(1L, 2L)));

        verify(deliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("境界: 受信者 0 人なら休業行も読まず配送しない")
    void 受信者ゼロは何もしない() {
        listener.onEmergencyClosureNotification(new EmergencyClosureNotificationEvent(CLOSURE_ID, List.of()));

        verify(closureRepository, never()).findById(any());
        verify(deliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("元データ（休業行）が無い／読めないなら配送を中止し、例外は外に出さない")
    void 元データが無ければ中止() {
        given(closureRepository.findById(CLOSURE_ID)).willReturn(Optional.empty());

        listener.onEmergencyClosureNotification(new EmergencyClosureNotificationEvent(CLOSURE_ID, List.of(1L)));

        verify(deliveryRunner, never()).sendOne(any());
    }
}
