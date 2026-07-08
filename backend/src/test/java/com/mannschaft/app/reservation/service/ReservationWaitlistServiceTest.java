package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.WaitlistStatus;
import com.mannschaft.app.reservation.dto.WaitlistCountResponse;
import com.mannschaft.app.reservation.dto.WaitlistEntryResponse;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReservationWaitlistService} の単体テスト（F03.4.5 §6.1・受け入れ条件 W-1/W-2 の中核）。
 *
 * <p>登録（満席のみ）・満席でない枠 400・重複 409・上限・本人取消・IDOR 404・
 * 予約成立時の CONVERTED 消し込み・空き復帰時の一斉通知（60 分抑制・非 AVAILABLE スキップ）・
 * 失効クリーンアップ・レートリミット 429 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationWaitlistService 単体テスト（F03.4.5 §6.1 キャンセル待ち）")
class ReservationWaitlistServiceTest {

    private static final Long TEAM_ID = 700L;
    private static final Long SLOT_ID = 8001L;
    private static final Long USER_ID = 900L;
    private static final LocalDate FUTURE_DATE = LocalDate.of(2026, 8, 1);

    @Mock
    private ReservationWaitlistEntryRepository waitlistRepository;
    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private ValkeyRateLimiter rateLimiter;
    @Mock
    private NotificationHelper notificationHelper;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private ReservationWaitlistService service;

    @BeforeEach
    void setUp() {
        service = new ReservationWaitlistService(
                waitlistRepository, slotRepository, viewAccessGuard, rateLimiter, notificationHelper, clock);
    }

    private ReservationSlotEntity slot(SlotStatus status) {
        return ReservationSlotEntity.builder()
                .id(SLOT_ID).teamId(TEAM_ID)
                .slotDate(FUTURE_DATE).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                .capacity(1).slotStatus(status).title("枠A")
                .build();
    }

    private RateLimitResult allowed() {
        return new RateLimitResult(true, 10, 9, 0L, 1L);
    }

    private void stubAllowedRate() {
        when(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any(Duration.class))).thenReturn(allowed());
    }

    // ────────────────────────────────────────────────────────────
    // 登録（W-1）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W-1: 満席枠へ登録すると WAITING が 1 件保存され応答に枠情報が載る")
    void 満席枠へ登録成功() {
        stubAllowedRate();
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(slot(SlotStatus.FULL)));
        when(waitlistRepository.existsBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(false);
        when(waitlistRepository.countByUserIdAndStatus(USER_ID, WaitlistStatus.WAITING)).thenReturn(0L);
        when(waitlistRepository.countBySlotIdAndStatus(SLOT_ID, WaitlistStatus.WAITING)).thenReturn(0L);
        when(waitlistRepository.save(any())).thenAnswer(inv -> {
            ReservationWaitlistEntryEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        WaitlistEntryResponse response = service.register(TEAM_ID, SLOT_ID, USER_ID);

        assertThat(response.getStatus()).isEqualTo("WAITING");
        assertThat(response.getSlotId()).isEqualTo(SLOT_ID);
        assertThat(response.getSlotDate()).isEqualTo(FUTURE_DATE);
        ArgumentCaptor<ReservationWaitlistEntryEntity> captor =
                ArgumentCaptor.forClass(ReservationWaitlistEntryEntity.class);
        verify(waitlistRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        verify(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);
    }

    @Test
    @DisplayName("W-1: 満席でない（AVAILABLE）枠への登録は 400=WAITLIST_SLOT_NOT_FULL")
    void 満席でない枠は400() {
        stubAllowedRate();
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(slot(SlotStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITLIST_SLOT_NOT_FULL);
        verify(waitlistRepository, never()).save(any());
    }

    @Test
    @DisplayName("W-1: CLOSED 枠は SLOT_CLOSED、過去枠は PAST_DATE_RESERVATION")
    void 過去やCLOSED枠は既存検証() {
        stubAllowedRate();
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(slot(SlotStatus.CLOSED)));
        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.SLOT_CLOSED);

        ReservationSlotEntity past = ReservationSlotEntity.builder()
                .id(SLOT_ID).teamId(TEAM_ID)
                .slotDate(LocalDate.of(2026, 7, 1)).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                .capacity(1).slotStatus(SlotStatus.FULL).build();
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(past));
        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.PAST_DATE_RESERVATION);
    }

    @Test
    @DisplayName("W-1: 二重登録は 409=WAITLIST_ALREADY_REGISTERED")
    void 二重登録は409() {
        stubAllowedRate();
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(slot(SlotStatus.FULL)));
        when(waitlistRepository.existsBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(true);

        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITLIST_ALREADY_REGISTERED);
    }

    @Test
    @DisplayName("W-1: ユーザー 10 件到達 / 枠 50 件到達で 400=WAITLIST_LIMIT_EXCEEDED")
    void 上限超過は400() {
        stubAllowedRate();
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(slot(SlotStatus.FULL)));
        when(waitlistRepository.existsBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(false);
        when(waitlistRepository.countByUserIdAndStatus(USER_ID, WaitlistStatus.WAITING)).thenReturn(10L);

        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITLIST_LIMIT_EXCEEDED);

        // 枠上限
        when(waitlistRepository.countByUserIdAndStatus(USER_ID, WaitlistStatus.WAITING)).thenReturn(0L);
        when(waitlistRepository.countBySlotIdAndStatus(SLOT_ID, WaitlistStatus.WAITING)).thenReturn(50L);
        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITLIST_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("W-1: 非会員かつ非公開は view ゲートで 403（登録に到達しない）")
    void 非会員は403() {
        doThrow(new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                .when(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
        verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("W-5類型: 登録レートリミット超過は 429=WAITLIST_RATE_LIMITED")
    void 登録レートリミット超過は429() {
        when(rateLimiter.tryConsume(eq("reservation-waitlist"), eq("user:" + USER_ID), eq(10), any(Duration.class)))
                .thenReturn(new RateLimitResult(false, 10, 0, 0L, 60L));

        assertThatThrownBy(() -> service.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITLIST_RATE_LIMITED);
        verify(slotRepository, never()).findByIdAndTeamId(anyLong(), anyLong());
    }

    // ────────────────────────────────────────────────────────────
    // 本人取消・IDOR（W-1）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W-1: 本人取消で WAITING が CANCELLED になる")
    void 本人取消() {
        ReservationWaitlistEntryEntity entry = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(USER_ID).status(WaitlistStatus.WAITING).build();
        when(waitlistRepository.findBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(Optional.of(entry));

        service.cancelOwn(TEAM_ID, SLOT_ID, USER_ID);

        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.CANCELLED);
        verify(waitlistRepository).save(entry);
    }

    @Test
    @DisplayName("W-1(IDOR): 他人の枠エントリを取消しようとしても自分の WAITING が無いため 404 秘匿")
    void 他人entry取消は404秘匿() {
        // userId 絞り込みで解決するため、他人のエントリしか無い場合は空 → 404。
        when(waitlistRepository.findBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOwn(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITLIST_ENTRY_NOT_FOUND);
        verify(waitlistRepository, never()).save(any());
    }

    // ────────────────────────────────────────────────────────────
    // 枠別件数（ADMIN・IDOR 404）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W-1: 枠別件数は WAITING 数を返す。他チーム枠は 404 秘匿")
    void 枠別件数と他チーム404() {
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(slot(SlotStatus.FULL)));
        when(waitlistRepository.countBySlotIdAndStatus(SLOT_ID, WaitlistStatus.WAITING)).thenReturn(3L);
        WaitlistCountResponse count = service.countWaiting(TEAM_ID, SLOT_ID);
        assertThat(count.getWaitingCount()).isEqualTo(3L);

        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.countWaiting(TEAM_ID, SLOT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
    }

    // ────────────────────────────────────────────────────────────
    // CONVERTED 消し込み
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W-2: 予約成立時、同一 (slot, user) の WAITING を CONVERTED へ消し込む（無ければ何もしない）")
    void 成立時のCONVERTED消し込み() {
        ReservationWaitlistEntryEntity entry = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(USER_ID).status(WaitlistStatus.WAITING).build();
        when(waitlistRepository.findBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(Optional.of(entry));
        service.markConvertedIfExists(SLOT_ID, USER_ID);
        assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.CONVERTED);
        verify(waitlistRepository).save(entry);

        when(waitlistRepository.findBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(Optional.empty());
        service.markConvertedIfExists(SLOT_ID, USER_ID); // 例外を投げない
    }

    // ────────────────────────────────────────────────────────────
    // 空き復帰時の一斉通知（W-2）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W-2: 空き復帰で WAITING 全員へ HIGH 通知し notified_at を更新する")
    void 空き復帰で全員通知() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.AVAILABLE)));
        ReservationWaitlistEntryEntity e1 = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(901L).status(WaitlistStatus.WAITING).build();
        ReservationWaitlistEntryEntity e2 = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(902L).status(WaitlistStatus.WAITING).build();
        when(waitlistRepository.findBySlotIdAndStatusForUpdate(SLOT_ID, WaitlistStatus.WAITING)).thenReturn(List.of(e1, e2));

        service.notifySlotReopened(TEAM_ID, SLOT_ID);

        verify(notificationHelper).notify(eq(901L), eq("RESERVATION_WAITLIST_OPENING"),
                eq(NotificationPriority.HIGH), anyString(), anyString(),
                eq("RESERVATION"), eq(SLOT_ID), eq(NotificationScopeType.TEAM), eq(TEAM_ID), anyString(), eq(null));
        verify(notificationHelper).notify(eq(902L), eq("RESERVATION_WAITLIST_OPENING"),
                eq(NotificationPriority.HIGH), anyString(), anyString(),
                eq("RESERVATION"), eq(SLOT_ID), eq(NotificationScopeType.TEAM), eq(TEAM_ID), anyString(), eq(null));
        assertThat(e1.getNotifiedAt()).isNotNull();
        assertThat(e2.getNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("W-2: 60 分以内に通知済みのエントリには再送しない")
    void 再通知抑制60分() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.AVAILABLE)));
        LocalDateTime now = LocalDateTime.now(clock);
        ReservationWaitlistEntryEntity recent = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(901L).status(WaitlistStatus.WAITING)
                .notifiedAt(now.minusMinutes(30)).build();
        ReservationWaitlistEntryEntity stale = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(902L).status(WaitlistStatus.WAITING)
                .notifiedAt(now.minusMinutes(90)).build();
        when(waitlistRepository.findBySlotIdAndStatusForUpdate(SLOT_ID, WaitlistStatus.WAITING))
                .thenReturn(List.of(recent, stale));

        service.notifySlotReopened(TEAM_ID, SLOT_ID);

        verify(notificationHelper, never()).notify(eq(901L), anyString(), any(NotificationPriority.class),
                anyString(), anyString(), anyString(), anyLong(), any(), anyLong(), anyString(), any());
        verify(notificationHelper, times(1)).notify(eq(902L), anyString(), any(NotificationPriority.class),
                anyString(), anyString(), anyString(), anyLong(), any(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("W-2: 枠がまだ AVAILABLE でない（CLOSED 化された等）場合は通知しない")
    void 非AVAILABLEはスキップ() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.CLOSED)));
        service.notifySlotReopened(TEAM_ID, SLOT_ID);
        verify(notificationHelper, never()).notify(anyLong(), anyString(), any(NotificationPriority.class),
                anyString(), anyString(), anyString(), anyLong(), any(), anyLong(), anyString(), any());
        verify(waitlistRepository, never()).findBySlotIdAndStatusForUpdate(anyLong(), any());
    }

    // ────────────────────────────────────────────────────────────
    // 失効クリーンアップ
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("自動失効: 枠開始を過ぎた WAITING を物理削除する")
    void 失効クリーンアップ() {
        ReservationWaitlistEntryEntity expired = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(USER_ID).status(WaitlistStatus.WAITING).build();
        when(waitlistRepository.findExpiredWaiting(eq(WaitlistStatus.WAITING), any(LocalDate.class), any(LocalTime.class)))
                .thenReturn(List.of(expired));

        int purged = service.purgeExpiredWaiting();

        assertThat(purged).isEqualTo(1);
        verify(waitlistRepository).deleteAll(List.of(expired));
    }
}
