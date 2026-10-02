package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationNotificationRecipientEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationNotificationRecipientRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReservationRecipientEmailEventListener} の単体テスト（機能D・§8 D-1/D-2/D-7/D-9）。
 *
 * <ul>
 *   <li>D-1: 予約成立（AUTO/MANUAL 両方）で有効宛先ごとに 1 record enqueue される</li>
 *   <li>D-2: 本文の「日時」は来店日時（slot 日時）であって申込時刻ではない。メニュー・予約者名も含む</li>
 *   <li>D-7: is_enabled=false の宛先には送らない（{@code findByTeamIdAndIsEnabledTrue}）</li>
 *   <li>D-9: 1 宛先の送出失敗が他宛先を巻き込まない（行単位 try/catch）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationRecipientEmailEventListener 予約通知メール送出 (機能D)")
class ReservationRecipientEmailEventListenerTest {

    private static final Long TEAM_ID = 10L;
    private static final Long RESERVATION_ID = 555L;
    private static final Long SLOT_ID = 777L;
    private static final Long ACTOR_USER_ID = 99L;
    private static final LocalDate SLOT_DATE = LocalDate.of(2026, 7, 3);
    private static final LocalTime START_TIME = LocalTime.of(10, 0);

    @Mock
    private ReservationNotificationRecipientRepository recipientRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private EmailOutboxService emailOutboxService;

    @InjectMocks
    private ReservationRecipientEmailEventListener listener;

    private ReservationCreatedEvent event(ApprovalMode mode) {
        // bookedAtFormatted（申込時刻）はイベントに載るが本文の「日時」には使わない。
        return new ReservationCreatedEvent(
                TEAM_ID, RESERVATION_ID, ACTOR_USER_ID, mode, "整体60分コース", "2026/07/01 12:34");
    }

    private void stubReservationAndSlot() {
        ReservationEntity reservation = ReservationEntity.builder()
                .id(RESERVATION_ID).teamId(TEAM_ID).userId(ACTOR_USER_ID).reservationSlotId(SLOT_ID).build();
        ReservationSlotEntity slot = ReservationSlotEntity.builder()
                .id(SLOT_ID).teamId(TEAM_ID).slotDate(SLOT_DATE).startTime(START_TIME)
                .endTime(LocalTime.of(11, 0)).title("整体60分コース").build();
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));
    }

    private ReservationNotificationRecipientEntity recipient(String email) {
        return ReservationNotificationRecipientEntity.builder()
                .teamId(TEAM_ID).email(email).isEnabled(true).build();
    }

    @Test
    @DisplayName("D-1/D-2: AUTO 予約成立で有効宛先ごとに enqueue され、本文は来店日時（申込時刻でない）＋メニュー＋予約者名を含む")
    void 有効宛先ごとにenqueueされ本文が来店日時を含む() {
        when(recipientRepository.findByTeamIdAndIsEnabledTrue(TEAM_ID))
                .thenReturn(List.of(recipient("shop@example.com"), recipient("reserve@example.com")));
        stubReservationAndSlot();
        when(nameResolverService.resolveUserFullName(ACTOR_USER_ID)).thenReturn("山田 太郎");

        listener.onReservationCreated(event(ApprovalMode.AUTO));

        ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(emailOutboxService, times(2)).enqueue(captor.capture());

        List<EmailOutboxRequest> requests = captor.getAllValues();
        // 全 request 共通: templateKind / sourceDomain / locale / userId=null
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.templateKind()).isEqualTo("RESERVATION_RECEIVED_NOTIFY");
            assertThat(r.sourceDomain()).isEqualTo("reservation");
            assertThat(r.locale()).isEqualTo("ja");
            assertThat(r.userId()).isNull();
            String body = r.payloadVars().get("body");
            assertThat(r.payloadVars().get("subject")).isNotBlank();
            // 来店日時（slot_date + start_time）が本文に入る。
            assertThat(body).contains("2026/07/03 10:00");
            // 申込時刻（bookedAtFormatted）は「日時」としては使わない（別項の補助のみ）。
            assertThat(body).doesNotContain("日時: 2026/07/01 12:34");
            assertThat(body).contains("整体60分コース");
            assertThat(body).contains("山田 太郎");
        });
        // 宛先アドレスと冪等キーが宛先ごとに分かれる。
        assertThat(requests).extracting(EmailOutboxRequest::toAddress)
                .containsExactlyInAnyOrder("shop@example.com", "reserve@example.com");
        assertThat(requests).extracting(EmailOutboxRequest::sourceEventId)
                .containsExactlyInAnyOrder(
                        "reservation-notify:" + RESERVATION_ID + ":shop@example.com",
                        "reservation-notify:" + RESERVATION_ID + ":reserve@example.com");
    }

    @Test
    @DisplayName("D-1: MANUAL 予約成立でも enqueue される（承認待ちでも店側に通知）")
    void MANUALでも送出する() {
        when(recipientRepository.findByTeamIdAndIsEnabledTrue(TEAM_ID))
                .thenReturn(List.of(recipient("shop@example.com")));
        stubReservationAndSlot();
        when(nameResolverService.resolveUserFullName(ACTOR_USER_ID)).thenReturn("山田 太郎");

        listener.onReservationCreated(event(ApprovalMode.MANUAL));

        verify(emailOutboxService, times(1)).enqueue(any());
    }

    @Test
    @DisplayName("D-7: 有効宛先が0件なら enqueue しない（reservation/slot 解決も走らない）")
    void 有効宛先0件は送出なし() {
        when(recipientRepository.findByTeamIdAndIsEnabledTrue(TEAM_ID)).thenReturn(List.of());

        listener.onReservationCreated(event(ApprovalMode.AUTO));

        verify(emailOutboxService, never()).enqueue(any());
        verify(reservationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("D-9: 1 宛先の enqueue 失敗が他宛先を巻き込まない（行単位 try/catch）")
    void 一宛先の失敗は他を巻き込まない() {
        when(recipientRepository.findByTeamIdAndIsEnabledTrue(TEAM_ID))
                .thenReturn(List.of(recipient("bad@example.com"), recipient("good@example.com")));
        stubReservationAndSlot();
        when(nameResolverService.resolveUserFullName(ACTOR_USER_ID)).thenReturn("山田 太郎");
        // 1 通目で例外、2 通目は成功させる。
        doThrow(new RuntimeException("enqueue 失敗"))
                .doReturn(java.util.UUID.randomUUID())
                .when(emailOutboxService).enqueue(any());

        listener.onReservationCreated(event(ApprovalMode.AUTO));

        // 失敗しても 2 通分 enqueue が試行される（1 通目の失敗が握りつぶされ 2 通目へ進む）。
        verify(emailOutboxService, times(2)).enqueue(any());
    }
}
