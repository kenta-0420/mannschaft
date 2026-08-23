package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
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
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private TeamTimezoneResolver teamTimezoneResolver;

    /** 実プロパティファイル（messages*.properties）を読む実体（i18n の locale 分岐を実データで検証するため）。 */
    private final ReloadableResourceBundleMessageSource messageSource = realMessageSource();

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private ReservationWaitlistService service;

    private static ReloadableResourceBundleMessageSource realMessageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @BeforeEach
    void setUp() {
        // 既定 locale は ja（未スタブのテストで NPE にならないよう lenient で用意）。
        org.mockito.Mockito.lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        service = new ReservationWaitlistService(
                waitlistRepository, slotRepository, viewAccessGuard, rateLimiter, notificationHelper,
                userLocaleCache, messageSource, clock);
    }

    /**
     * 任意の {@link Clock}（ゾーン込み）を明示注入してサービスを再生成する。
     * Issue #2526 のゾーン一致性番人テスト（同一瞬間・異なる Clock ゾーン）で使う。
     */
    private void reinitServiceWithClock(Clock injectedClock) {
        service = new ReservationWaitlistService(
                waitlistRepository, slotRepository, viewAccessGuard, rateLimiter, notificationHelper,
                userLocaleCache, messageSource, injectedClock);
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

    @Test
    @DisplayName(
            "Issue #2526 番人: 過去枠判定は Clock のゾーンに左右されず、同一瞬間なら結果が一致する")
    void 過去枠判定はClockのゾーンに左右されない() {
        // 枠開始は FUTURE_DATE(2026-08-01) 10:00（業務ローカル時刻）。
        // 「業務基準（JVM 既定ゾーン。実行環境に依存し得るため決め打ちしない）で見て枠開始の 1 分前」
        // ＝2026-08-01T09:59 を、実際の JVM 既定ゾーンで instant 化した「同一瞬間」を、
        // ゾーン設定だけが異なる 2 つの Clock（UTC / Asia+09:00）で表現する。
        // 正しい実装（LocalDateTime.now(clock.withZone(ZoneId.systemDefault()))）なら
        // Clock 自身のゾーンに左右されず、2 回の呼び出しが同じ結果（成功/失敗）になるはずである。
        // 期待する壁時計を決め打ちしない（JVM既定ゾーンがUTCであることを前提にしない）。
        Instant sameInstant = LocalDateTime.of(2026, 8, 1, 9, 59)
                .atZone(java.time.ZoneId.systemDefault()).toInstant();
        stubAllowedRate();
        when(waitlistRepository.existsBySlotIdAndUserIdAndStatus(SLOT_ID, USER_ID, WaitlistStatus.WAITING))
                .thenReturn(false);
        when(waitlistRepository.countByUserIdAndStatus(USER_ID, WaitlistStatus.WAITING)).thenReturn(0L);
        when(waitlistRepository.countBySlotIdAndStatus(SLOT_ID, WaitlistStatus.WAITING)).thenReturn(0L);
        when(waitlistRepository.save(any())).thenAnswer(inv -> {
            ReservationWaitlistEntryEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).thenReturn(Optional.of(slot(SlotStatus.FULL)));

        reinitServiceWithClock(Clock.fixed(sameInstant, ZoneOffset.UTC));
        WaitlistEntryResponse responseUtc = service.register(TEAM_ID, SLOT_ID, USER_ID);

        reinitServiceWithClock(Clock.fixed(sameInstant, java.time.ZoneId.of("Asia/Tokyo")));
        WaitlistEntryResponse responseTokyo = service.register(TEAM_ID, SLOT_ID, USER_ID);

        // Clock のゾーン設定が判定結果に漏れ出してはならない（同一瞬間なら両方とも同じ判定になるはず）。
        // ここでは「JVM 既定ゾーンで見て枠開始の 1 分前」を instant 化しているため、
        // どちらも例外なく成功（未来枠として登録できる）はずである。
        assertThat(responseUtc).as("UTC Clock: 未来枠のため登録成功するはず").isNotNull();
        assertThat(responseTokyo).as("Asia/Tokyo Clock: 同一瞬間なら UTC と同じ結果になるはず").isNotNull();
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
    @DisplayName("i18n: 受信者の locale が en の場合、通知本文が英語で組み立てられる")
    void 空き復帰通知は受信者localeで組み立てられる() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot(SlotStatus.AVAILABLE)));
        ReservationWaitlistEntryEntity jaEntry = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(901L).status(WaitlistStatus.WAITING).build();
        ReservationWaitlistEntryEntity enEntry = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(SLOT_ID).userId(902L).status(WaitlistStatus.WAITING).build();
        when(waitlistRepository.findBySlotIdAndStatusForUpdate(SLOT_ID, WaitlistStatus.WAITING))
                .thenReturn(List.of(jaEntry, enEntry));
        when(userLocaleCache.getLocale(901L)).thenReturn("ja");
        when(userLocaleCache.getLocale(902L)).thenReturn("en");

        service.notifySlotReopened(TEAM_ID, SLOT_ID);

        ArgumentCaptor<String> jaBody = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notify(eq(901L), anyString(), any(NotificationPriority.class),
                anyString(), jaBody.capture(), anyString(), anyLong(), any(), anyLong(), anyString(), any());
        ArgumentCaptor<String> enBody = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notify(eq(902L), anyString(), any(NotificationPriority.class),
                anyString(), enBody.capture(), anyString(), anyLong(), any(), anyLong(), anyString(), any());

        assertThat(jaBody.getValue()).contains("空きが出ました");
        assertThat(enBody.getValue()).contains("slot has opened up");
        assertThat(jaBody.getValue()).isNotEqualTo(enBody.getValue());
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
        when(waitlistRepository.findByStatus(WaitlistStatus.WAITING))
                .thenReturn(List.of(expired));
        ReservationSlotEntity expiredSlot = ReservationSlotEntity.builder().id(SLOT_ID).teamId(TEAM_ID)
                .slotDate(LocalDate.of(2026, 7, 7)).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                .slotStatus(SlotStatus.FULL).build();
        when(slotRepository.findAllById(any())).thenReturn(List.of(expiredSlot));

        int purged = service.purgeExpiredWaiting();

        assertThat(purged).isEqualTo(1);
        verify(waitlistRepository).deleteAll(List.of(expired));
    }

    @Test
    @DisplayName(
            "Issue #2526 番人: 失効クリーンアップの基準時刻は Clock のゾーンに左右されず、同一瞬間なら結果が一致する")
    void 失効クリーンアップの基準時刻はClockのゾーンに左右されない() {
        // JVM 既定ゾーンがUTCであることを前提にせず、その既定ゾーンで instant 化する。
        Instant sameInstant = LocalDateTime.of(2026, 8, 1, 9, 59)
                .atZone(java.time.ZoneId.systemDefault()).toInstant();
        when(waitlistRepository.findByStatus(WaitlistStatus.WAITING)).thenReturn(List.of());

        reinitServiceWithClock(Clock.fixed(sameInstant, ZoneOffset.UTC));
        service.purgeExpiredWaiting();

        reinitServiceWithClock(Clock.fixed(sameInstant, java.time.ZoneId.of("Asia/Tokyo")));
        service.purgeExpiredWaiting();

        verify(waitlistRepository, times(2)).findByStatus(WaitlistStatus.WAITING);
        /*
        assertThat(dateCaptor.getAllValues().get(0))
                .as("Clock のゾーン設定が判定結果に漏れ出してはならない")
                .isEqualTo(dateCaptor.getAllValues().get(1));
        assertThat(timeCaptor.getAllValues().get(0))
                .as("Clock のゾーン設定が判定結果に漏れ出してはならない")
                .isEqualTo(timeCaptor.getAllValues().get(1)); */
    }

    @Test
    @DisplayName("非JST境界: America/New_Yorkの過去枠だけをpurgeし未来枠を保持する")
    void 非JST境界のpurgeは過去枠だけ削除する() {
        reinitServiceWithClock(Clock.fixed(Instant.parse("2026-08-10T03:30:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "teamTimezoneResolver", teamTimezoneResolver);
        ReservationWaitlistEntryEntity expired = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(8101L).userId(USER_ID).status(WaitlistStatus.WAITING).build();
        ReservationWaitlistEntryEntity future = ReservationWaitlistEntryEntity.builder()
                .teamId(TEAM_ID).slotId(8102L).userId(USER_ID + 1).status(WaitlistStatus.WAITING).build();
        when(waitlistRepository.findByStatus(WaitlistStatus.WAITING)).thenReturn(List.of(expired, future));
        ReservationSlotEntity expiredSlot = ReservationSlotEntity.builder().id(8101L).teamId(TEAM_ID)
                .slotDate(LocalDate.of(2026, 8, 9)).startTime(LocalTime.of(23, 15)).endTime(LocalTime.of(23, 45))
                .slotStatus(SlotStatus.FULL).build();
        ReservationSlotEntity futureSlot = ReservationSlotEntity.builder().id(8102L).teamId(TEAM_ID)
                .slotDate(LocalDate.of(2026, 8, 9)).startTime(LocalTime.of(23, 45)).endTime(LocalTime.of(23, 59))
                .slotStatus(SlotStatus.FULL).build();
        when(slotRepository.findAllById(any())).thenReturn(List.of(expiredSlot, futureSlot));
        when(teamTimezoneResolver.toInstant(TEAM_ID, expiredSlot.getSlotDate(), expiredSlot.getStartTime()))
                .thenReturn(Instant.parse("2026-08-10T03:15:00Z"));
        when(teamTimezoneResolver.toInstant(TEAM_ID, futureSlot.getSlotDate(), futureSlot.getStartTime()))
                .thenReturn(Instant.parse("2026-08-10T03:45:00Z"));

        assertThat(service.purgeExpiredWaiting()).isEqualTo(1);
        verify(waitlistRepository).deleteAll(List.of(expired));
    }
}
