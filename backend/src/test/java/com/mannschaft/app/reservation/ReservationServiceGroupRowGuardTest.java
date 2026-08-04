package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 単票 API へのグループ行ガード（400 = RESERVATION_042）の回帰テスト（F03.4.3 §4 / §5.1）。
 *
 * <p>既存の単票状態遷移メソッド 6 本（cancelByUser / cancelByAdmin / confirmReservation /
 * completeReservation / markNoShow / rescheduleReservation）はグループ所属行
 * （{@code group_id IS NOT NULL}）に対して 400=042 を throw し、部分遷移によるグループ状態の分裂・
 * booked_count 不整合を構造的に防ぐ。メモ更新（admin-note）は<b>非代表行のみ</b> 042 で拒否し、
 * 代表行は許可する（非代表行のメモは一覧に浮上せず事実上消失するため・§4）。
 * 単枠予約（group_id NULL）は従来どおり通過することも対で検証する（G-11 の UT 面）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("単票APIのグループ行ガード（RESERVATION_042）回帰テスト")
class ReservationServiceGroupRowGuardTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private ReservationSlotService slotService;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private com.mannschaft.app.common.NameResolverService nameResolverService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private com.mannschaft.app.common.AccessControlService accessControlService;
    @Mock
    private com.mannschaft.app.reservation.service.ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private com.mannschaft.app.reservation.service.ReservationPolicyService reservationPolicyService;
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository blockedTimeRepository;
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository
            recurringBlockedTimeRepository;
    @Mock
    private com.mannschaft.app.reservation.service.ReservationGroupSummaryResolver groupSummaryResolver;

    private final com.mannschaft.app.reservation.service.ReservationUnavailabilityChecker unavailabilityChecker =
            new com.mannschaft.app.reservation.service.ReservationUnavailabilityChecker();

    private ReservationService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long RESERVATION_ID = 10L;
    private static final Long SLOT_ID = 20L;
    private static final Long LINE_ID = 30L;
    private static final UUID GROUP_ID = UUID.randomUUID();

    /** 枠開始（2026-04-01 10:00）より十分前に固定し、締切判定がガード検証を妨げないようにする。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        service = new ReservationService(
                reservationRepository, slotRepository, lineRepository, slotService, reservationMapper,
                nameResolverService, eventPublisher, accessControlService, viewAccessGuard,
                reservationPolicyService, blockedTimeRepository, recurringBlockedTimeRepository,
                unavailabilityChecker,
                groupSummaryResolver,
                org.mockito.Mockito.mock(com.mannschaft.app.reservation.service.ReservationWaitlistService.class),
                // F03.4.5 §6.4: レートリミットは本テストの対象外のため素通しの mock。
                org.mockito.Mockito.mock(com.mannschaft.app.reservation.service.ReservationCreateRateLimiter.class),
                FIXED_CLOCK);

        given(slotRepository.findById(any())).willReturn(Optional.of(slotEntity()));
        given(lineRepository.findById(any())).willReturn(Optional.empty());
        given(slotRepository.findAllById(anyIterable())).willReturn(List.of());
        given(lineRepository.findAllById(anyIterable())).willReturn(List.of());
        given(reservationMapper.toReservationResponse(any(ReservationEntity.class), any(), any()))
                .willReturn(defaultResponse());
        given(nameResolverService.resolveUserFullName(any(Long.class))).willReturn("山田 太郎");
        given(slotService.getSlotEntity(any())).willReturn(slotEntity());
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).build());
        given(groupSummaryResolver.resolve(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(java.util.Map.of());
        willAnswer(inv -> inv.getArgument(0)).given(reservationRepository).save(any(ReservationEntity.class));
    }

    private static ReservationSlotEntity slotEntity() {
        return ReservationSlotEntity.builder()
                .id(SLOT_ID)
                .teamId(TEAM_ID)
                .slotDate(LocalDate.of(2026, 4, 1))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .build();
    }

    private static ReservationResponse defaultResponse() {
        return ReservationResponse.builder()
                .id(RESERVATION_ID)
                .identifier(new ReservationResponse.ReservationIdentifierDto(SLOT_ID, LINE_ID, TEAM_ID, USER_ID, null))
                .status(new ReservationResponse.ReservationStatusDto("PENDING", LocalDateTime.now(), null, null))
                .cancellation(new ReservationResponse.CancellationDto(null, null, null))
                .notes(new ReservationResponse.NotesDto(null, null))
                .audit(new ReservationResponse.ReservationAuditDto(null, null))
                .build();
    }

    /** グループ所属行（primary 指定可）。 */
    private ReservationEntity groupRow(boolean primary, ReservationStatus status) {
        ReservationEntity entity = ReservationEntity.builder()
                .id(RESERVATION_ID)
                .reservationSlotId(SLOT_ID)
                .lineId(LINE_ID)
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .groupId(GROUP_ID)
                .isGroupPrimary(primary)
                .status(status)
                .build();
        return entity;
    }

    /** 単枠予約行（group_id NULL・既存互換）。 */
    private ReservationEntity singleRow(ReservationStatus status) {
        return ReservationEntity.builder()
                .id(RESERVATION_ID)
                .reservationSlotId(SLOT_ID)
                .lineId(LINE_ID)
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .status(status)
                .build();
    }

    private void stubByTeam(ReservationEntity entity) {
        given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID)).willReturn(Optional.of(entity));
    }

    private void stubByUser(ReservationEntity entity) {
        given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID)).willReturn(Optional.of(entity));
    }

    private void assert042(Runnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.GROUP_ROW_DIRECT_OPERATION_NOT_ALLOWED);
    }

    // ── 6 本の単票状態遷移はグループ行で 400=042 ──────────────────

    @Test
    @DisplayName("①cancelByUser: グループ行は 042")
    void cancelByUser_グループ行は042() {
        stubByUser(groupRow(true, ReservationStatus.CONFIRMED));
        assert042(() -> service.cancelByUser(USER_ID, RESERVATION_ID, new CancelReservationRequest("x", null)));
    }

    @Test
    @DisplayName("②cancelByAdmin: グループ行は 042")
    void cancelByAdmin_グループ行は042() {
        stubByTeam(groupRow(true, ReservationStatus.CONFIRMED));
        assert042(() -> service.cancelByAdmin(TEAM_ID, RESERVATION_ID, new CancelReservationRequest("x", null)));
    }

    @Test
    @DisplayName("③confirmReservation: グループ行は 042（G-7 の単票封じ）")
    void confirm_グループ行は042() {
        stubByTeam(groupRow(true, ReservationStatus.PENDING));
        assert042(() -> service.confirmReservation(TEAM_ID, RESERVATION_ID));
    }

    @Test
    @DisplayName("④completeReservation: グループ行は 042")
    void complete_グループ行は042() {
        stubByTeam(groupRow(true, ReservationStatus.CONFIRMED));
        assert042(() -> service.completeReservation(TEAM_ID, RESERVATION_ID));
    }

    @Test
    @DisplayName("⑤markNoShow: グループ行は 042")
    void noShow_グループ行は042() {
        stubByTeam(groupRow(true, ReservationStatus.CONFIRMED));
        assert042(() -> service.markNoShow(TEAM_ID, RESERVATION_ID));
    }

    @Test
    @DisplayName("⑥rescheduleReservation: グループ行は 042（1枠だけ移動して連続性が壊れるのを防ぐ）")
    void reschedule_グループ行は042() {
        stubByTeam(groupRow(true, ReservationStatus.CONFIRMED));
        assert042(() -> service.rescheduleReservation(TEAM_ID, RESERVATION_ID, new RescheduleRequest(99L)));
    }

    @Test
    @DisplayName("非代表行も同様に 042（代表行かどうかは状態遷移では区別しない）")
    void 非代表行の単票遷移も042() {
        stubByTeam(groupRow(false, ReservationStatus.PENDING));
        assert042(() -> service.confirmReservation(TEAM_ID, RESERVATION_ID));
    }

    // ── メモ更新（admin-note）: 非代表行のみ 042・代表行は許可 ──────

    @Test
    @DisplayName("メモ更新: 非代表行への更新は 042（一覧に浮上せず消失するサイレントデータロス防止・§4）")
    void メモ更新_非代表行は042() {
        stubByTeam(groupRow(false, ReservationStatus.CONFIRMED));
        assert042(() -> service.updateAdminNote(TEAM_ID, RESERVATION_ID, new AdminNoteRequest("メモ")));
    }

    @Test
    @DisplayName("メモ更新: 代表行への更新は許可される")
    void メモ更新_代表行は許可() {
        ReservationEntity primary = groupRow(true, ReservationStatus.CONFIRMED);
        stubByTeam(primary);

        service.updateAdminNote(TEAM_ID, RESERVATION_ID, new AdminNoteRequest("代表行メモ"));

        assertThat(primary.getAdminNote()).isEqualTo("代表行メモ");
    }

    // ── 単枠予約（group_id NULL）は従来どおり通過（G-11 の UT 面）────

    @Test
    @DisplayName("G-11: 単枠行の confirm は従来どおり成功する")
    void 単枠confirmは通過() {
        ReservationEntity single = singleRow(ReservationStatus.PENDING);
        stubByTeam(single);

        service.confirmReservation(TEAM_ID, RESERVATION_ID);

        assertThat(single.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("G-11: 単枠行の本人キャンセル（締切内）は従来どおり成功する")
    void 単枠キャンセルは通過() {
        ReservationEntity single = singleRow(ReservationStatus.CONFIRMED);
        stubByUser(single);

        service.cancelByUser(USER_ID, RESERVATION_ID, new CancelReservationRequest("予定変更", null));

        assertThat(single.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("G-11: 単枠行のメモ更新は従来どおり成功する")
    void 単枠メモ更新は通過() {
        ReservationEntity single = singleRow(ReservationStatus.CONFIRMED);
        stubByTeam(single);

        service.updateAdminNote(TEAM_ID, RESERVATION_ID, new AdminNoteRequest("メモ"));

        assertThat(single.getAdminNote()).isEqualTo("メモ");
    }
}
