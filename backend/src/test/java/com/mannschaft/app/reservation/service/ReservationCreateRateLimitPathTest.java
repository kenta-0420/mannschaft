package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateReservationGroupRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 予約作成レートリミットが<b>単枠・グループの両経路から実際に到達する</b>ことの検証
 * （F03.4.5 §6.4・受け入れ条件 AC-6-11 / AC-6-12）。
 *
 * <p><b>なぜこのテストが要るのか</b>: 「共通ヘルパを作ったが片方の経路が使っていない」は当リポの典型事故で、
 * 定数を 1 箇所に寄せただけでは防げない（片方が呼び忘れていても定数テストは緑になる）。
 * 本テストは <b>実物の {@link ReservationCreateRateLimiter}</b> を両サービスへ注入し、
 * 実際に {@link ValkeyRateLimiter#tryConsume} が同一 zone で消費されることを実経路で観測する。</p>
 *
 * <p>両サービスの他の協力オブジェクトは mock で、レートリミット消費<b>直後</b>で処理が止まるように
 * 仕向けている（単枠は枠解決で 404、グループは tx テンプレートが素通り）。消費はどちらも
 * その手前で起きるため、経路到達の観測に影響しない。</p>
 *
 * <p>CI に Valkey は存在せず {@link ValkeyRateLimiter} は Bean 不在時 fail-open で通すため、
 * 「6 回目が 429」を実 Valkey 前提で書くと偽 green になる。よって {@link ValkeyRateLimiter} を
 * mock して回数判定を制御する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("予約作成レートリミット 経路到達テスト（F03.4.5 §6.4 / AC-6-11・AC-6-12）")
class ReservationCreateRateLimitPathTest {

    private static final Long TEAM_ID = 77L;
    private static final Long USER_ID = 555L;
    private static final Long SLOT_ID = 900L;
    private static final Long LINE_ID = 800L;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    @Mock
    private ValkeyRateLimiter valkeyRateLimiter;
    @Mock
    private ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private ReservationSlotService slotService;
    @Mock
    private ReservationSlotRepository slotRepository;

    private ReservationCreateRateLimiter createRateLimiter;
    private ReservationService reservationService;
    private ReservationGroupService groupService;

    @BeforeEach
    void setUp() {
        // 実物のレートリミッタを両サービスへ「同一インスタンス」で注入する（バケット共有の実体）。
        createRateLimiter = new ReservationCreateRateLimiter(valkeyRateLimiter);

        reservationService = new ReservationService(
                mock(com.mannschaft.app.reservation.repository.ReservationRepository.class),
                slotRepository,
                mock(com.mannschaft.app.reservation.repository.ReservationLineRepository.class),
                slotService,
                mock(com.mannschaft.app.reservation.ReservationMapper.class),
                mock(com.mannschaft.app.common.NameResolverService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.mannschaft.app.common.AccessControlService.class),
                viewAccessGuard,
                mock(ReservationPolicyService.class),
                mock(com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository.class),
                mock(com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository.class),
                new ReservationUnavailabilityChecker(),
                mock(ReservationGroupSummaryResolver.class),
                mock(ReservationWaitlistService.class),
                createRateLimiter,
                FIXED_CLOCK);

        groupService = new ReservationGroupService(
                mock(com.mannschaft.app.reservation.repository.ReservationRepository.class),
                slotRepository,
                slotService,
                mock(com.mannschaft.app.reservation.repository.ReservationLineRepository.class),
                mock(com.mannschaft.app.reservation.repository.ReservationMenuRepository.class),
                mock(com.mannschaft.app.reservation.repository.ReservationMenuLineRepository.class),
                mock(com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository.class),
                mock(com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository.class),
                mock(com.mannschaft.app.reservation.repository.ReservationReminderRepository.class),
                viewAccessGuard,
                mock(ReservationPolicyService.class),
                new ReservationUnavailabilityChecker(),
                mock(com.mannschaft.app.common.AccessControlService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.mannschaft.app.auth.service.AuditLogService.class),
                mock(ReservationWaitlistService.class),
                createRateLimiter,
                // execute() を呼んでも中身を走らせない mock（レートリミット消費は execute の手前で起きる）。
                mock(TransactionTemplate.class),
                FIXED_CLOCK);

        // 単枠は枠解決で止める（レートリミット消費より後）。
        given(slotService.getSlotEntity(anyLong(), anyLong()))
                .willThrow(new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));
    }

    /** 単枠予約作成を 1 回試行する（消費後に 404 で止まる）。 */
    private void tryCreateSingle() {
        try {
            reservationService.createReservation(
                    TEAM_ID, USER_ID, new CreateReservationRequest(SLOT_ID, LINE_ID, null, null));
        } catch (BusinessException e) {
            if (e.getErrorCode() == ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED) {
                throw e;
            }
            // SLOT_NOT_FOUND は「消費より後で止めるための仕掛け」なので握って続行する。
        }
    }

    /** グループ予約作成を 1 回試行する（消費後に tx テンプレートが素通り）。 */
    private void tryCreateGroup() {
        groupService.createGroup(TEAM_ID, USER_ID,
                // 引数順: menuId, lineId, slotIds, userNote
                new CreateReservationGroupRequest(null, LINE_ID, List.of(SLOT_ID, SLOT_ID + 1), null));
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-12: 単枠とグループが同一 zone を消費する
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-12: 単枠・グループの両経路がレートリミッタに到達し、同一 zone・同一キーを消費する")
    void 両経路が同一バケットを消費する() {
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(new RateLimitResult(true, 5, 4, 0L, 1L));

        tryCreateSingle();
        tryCreateGroup();

        ArgumentCaptor<String> zone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valkeyRateLimiter, org.mockito.Mockito.times(2))
                .tryConsume(zone.capture(), key.capture(), anyInt(), any());

        assertThat(zone.getAllValues())
                .as("単枠・グループが別バケットだと単枠5回＋グループ5回の買い占めが可能になる")
                .containsExactly("reservation-create", "reservation-create");
        assertThat(key.getAllValues())
                .as("同一ユーザーは同一キーで数える")
                .containsExactly("user:" + USER_ID, "user:" + USER_ID);
    }

    // ────────────────────────────────────────────────────────────
    // 順序の対称性: 両経路とも「認可 → レート消費」であること（殿の裁定2・2026-07-29）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("裁定2: 単枠・グループとも認可(assertCanView)がレート消費より先に評価される")
    void 両経路とも認可がレート消費より先である() {
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(new RateLimitResult(true, 5, 4, 0L, 1L));

        tryCreateSingle();
        tryCreateGroup();

        // 呼び出し順序を検証する。同一 zone を共有しているのに片方だけ「認可前に消費」だと、
        // 403 のはずが 429 で返る状況が生まれ調査コストになる。
        InOrder single = inOrder(viewAccessGuard, valkeyRateLimiter);
        single.verify(viewAccessGuard, org.mockito.Mockito.atLeastOnce()).assertCanView(TEAM_ID, USER_ID);
        single.verify(valkeyRateLimiter, org.mockito.Mockito.atLeastOnce())
                .tryConsume(anyString(), anyString(), anyInt(), any());
        // 認可 2 回（単枠・グループ）に対しレート消費も 2 回で、いずれも認可が先行している。
        verify(viewAccessGuard, org.mockito.Mockito.times(2)).assertCanView(TEAM_ID, USER_ID);
        verify(valkeyRateLimiter, org.mockito.Mockito.times(2))
                .tryConsume(anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("裁定2: 認可で弾かれたグループ作成はレート枠を消費しない（403 が 429 に化けない）")
    void 認可拒否のグループ作成はレートを消費しない() {
        org.mockito.BDDMockito.willThrow(
                        new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                .given(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

        assertThatThrownBy(this::tryCreateGroup)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);

        verify(valkeyRateLimiter, org.mockito.Mockito.never())
                .tryConsume(anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("裁定2: 認可で弾かれた単枠作成もレート枠を消費しない")
    void 認可拒否の単枠作成はレートを消費しない() {
        org.mockito.BDDMockito.willThrow(
                        new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                .given(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

        assertThatThrownBy(() -> reservationService.createReservation(
                TEAM_ID, USER_ID, new CreateReservationRequest(SLOT_ID, LINE_ID, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);

        verify(valkeyRateLimiter, org.mockito.Mockito.never())
                .tryConsume(anyString(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("AC-6-12: 単枠のみを叩いた場合も必ず 1 回消費される（経路の呼び忘れ検知）")
    void 単枠経路が消費を呼ぶ() {
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(new RateLimitResult(true, 5, 4, 0L, 1L));

        tryCreateSingle();

        verify(valkeyRateLimiter).tryConsume(
                "reservation-create", "user:" + USER_ID, 5, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("AC-6-12: グループのみを叩いた場合も必ず 1 回消費される（経路の呼び忘れ検知）")
    void グループ経路が消費を呼ぶ() {
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(new RateLimitResult(true, 5, 4, 0L, 1L));

        tryCreateGroup();

        verify(valkeyRateLimiter).tryConsume(
                "reservation-create", "user:" + USER_ID, 5, Duration.ofMinutes(1));
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-11: 単枠 3 ＋ グループ 3 の混合で 6 回目が 429・窓明けで成功
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-11: 単枠3回＋グループ3回の混合で 6 回目が 429、窓が明ければ再び成功する")
    void 混合六回目が429で窓明けは成功する() {
        // 固定ウィンドウ（1 分・上限 5）を模す: 同一ウィンドウ内の 6 回目だけ拒否する。
        AtomicInteger consumedInWindow = new AtomicInteger();
        willAnswer(inv -> {
            int count = consumedInWindow.incrementAndGet();
            boolean allowed = count <= ReservationCreateRateLimiter.RATE_LIMIT;
            return new RateLimitResult(allowed, 5, Math.max(0, 5 - count), 0L, 60L);
        }).given(valkeyRateLimiter).tryConsume(anyString(), anyString(), anyInt(), any());

        // 1〜5 回目（単枠3 → グループ2）は通る
        tryCreateSingle();
        tryCreateSingle();
        tryCreateSingle();
        tryCreateGroup();
        tryCreateGroup();

        // 6 回目（グループ）は 429 = RESERVATION_053
        assertThatThrownBy(this::tryCreateGroup)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED);

        // 61 秒後 ＝ 固定ウィンドウが切り替わりカウンタがリセットされた状態
        consumedInWindow.set(0);
        assertThat(catchRateLimit(this::tryCreateSingle))
                .as("窓が明ければ再び作成できる")
                .isNull();
    }

    @Test
    @DisplayName("AC-6-11(順序逆): グループ3回＋単枠2回の後の単枠6回目も 429（どちらの経路でも上限は共通）")
    void 逆順の混合でも六回目が429() {
        AtomicInteger consumedInWindow = new AtomicInteger();
        willAnswer(inv -> {
            int count = consumedInWindow.incrementAndGet();
            return new RateLimitResult(count <= ReservationCreateRateLimiter.RATE_LIMIT,
                    5, Math.max(0, 5 - count), 0L, 60L);
        }).given(valkeyRateLimiter).tryConsume(anyString(), anyString(), anyInt(), any());

        tryCreateGroup();
        tryCreateGroup();
        tryCreateGroup();
        tryCreateSingle();
        tryCreateSingle();

        assertThatThrownBy(this::tryCreateSingle)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED);
    }

    /** レートリミット例外だけを拾う（他の例外は仕掛け由来なので握る）。 */
    private ReservationErrorCode catchRateLimit(Runnable action) {
        try {
            action.run();
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode() == ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED
                    ? ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED
                    : null;
        }
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-12: キャンセル待ち登録は予約作成バケットを消費しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-12: キャンセル待ち登録は別 zone（reservation-waitlist）で予約バケットを消費しない")
    void キャンセル待ちは予約バケットを消費しない() {
        ReservationWaitlistService waitlistService = new ReservationWaitlistService(
                mock(com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository.class),
                slotRepository,
                viewAccessGuard,
                valkeyRateLimiter,
                mock(com.mannschaft.app.notification.service.NotificationHelper.class),
                mock(com.mannschaft.app.common.i18n.UserLocaleCache.class),
                mock(org.springframework.context.MessageSource.class),
                FIXED_CLOCK);
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .willReturn(new RateLimitResult(true, 10, 9, 0L, 1L));
        given(slotRepository.findByIdAndTeamId(anyLong(), anyLong()))
                .willReturn(java.util.Optional.empty());

        // 枠解決で 404 になるが、レートリミット消費はその手前で済んでいる。
        assertThatThrownBy(() -> waitlistService.register(TEAM_ID, SLOT_ID, USER_ID))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<String> zone = ArgumentCaptor.forClass(String.class);
        verify(valkeyRateLimiter).tryConsume(zone.capture(), anyString(), anyInt(), any());
        assertThat(zone.getValue())
                .as("登録は軽量操作のため予約作成バケットを消費させない（§6.4）")
                .isEqualTo("reservation-waitlist")
                .isNotEqualTo(ReservationCreateRateLimiter.RATE_ZONE);
    }
}
