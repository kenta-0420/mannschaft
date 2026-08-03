package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.event.ReservationCancelledByMemberEvent;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import com.mannschaft.app.reservation.ReservationCancelScope;
import com.mannschaft.app.reservation.ReservationConfirmScope;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 定期予約の series スコープ操作テスト（F03.4.5 §6.2 W2-5）。
 *
 * <p>受け入れ条件の対応:</p>
 * <ul>
 *   <li><b>AC-5-7</b>: {@code THIS_ONLY} は当該1件のみ。{@code THIS_AND_FOLLOWING} は series 内の
 *       <b>当該日以降</b>の active 行のみ（過去回は不変）。締切超過の回はスキップして明細を返す</li>
 *   <li><b>AC-5-8</b>: 他人の行が同一 series に混ざっていても自分の予約以外はキャンセルされない（IDOR）</li>
 *   <li><b>AC-5-9</b>: {@code scope=SERIES} の confirm で series 内 PENDING を一括 CONFIRMED。
 *       他チームの行は掴めない（管理者認可を各行に適用）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("定期予約 series スコープ操作テスト（F03.4.5 §6.2 / AC-5-7・AC-5-8・AC-5-9）")
class ReservationRecurringSeriesScopeTest {

    private static final Long TEAM_ID = 6001L;
    private static final Long OTHER_TEAM_ID = 6099L;
    private static final Long USER_ID = 6002L;
    private static final Long OTHER_USER_ID = 6003L;
    private static final Long LINE_ID = 6004L;
    private static final UUID SERIES_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");

    /** 起点回は 2026-06-16（火）。過去回 2026-06-02 / 未来回 06-23・06-30 を用意する。 */
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 6, 16);
    private static final LocalTime START = LocalTime.of(19, 0);
    private static final LocalTime END = LocalTime.of(20, 0);

    /** 「今」= 2026-06-01。BASE_DATE の 24h 締切より十分前なので既定では全回キャンセル可。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

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
    private NameResolverService nameResolverService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private ReservationPolicyService reservationPolicyService;
    @Mock
    private ReservationBlockedTimeRepository blockedTimeRepository;
    @Mock
    private ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;
    @Mock
    private ReservationGroupSummaryResolver groupSummaryResolver;

    private ReservationService service;

    /** 採番済み予約の実体。id → entity。 */
    private final java.util.Map<Long, ReservationEntity> reservations = new java.util.LinkedHashMap<>();
    /** 採番済み枠の実体。id → entity。 */
    private final java.util.Map<Long, ReservationSlotEntity> slots = new java.util.LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        service = buildService(FIXED_CLOCK);
        // enrich() は identifier を再構築する（withUserName）ため、mapper のスタブは
        // identifier を持つレスポンスを返さなければならない（null だと NPE で本題に到達できない）。
        given(reservationMapper.toReservationResponse(any(ReservationEntity.class), any(), any()))
                .willAnswer(inv -> {
                    ReservationEntity e = inv.getArgument(0);
                    return ReservationResponse.builder()
                            .id(e.getId())
                            .identifier(new ReservationResponse.ReservationIdentifierDto(
                                    e.getReservationSlotId(), e.getLineId(), e.getTeamId(), e.getUserId(), null))
                            .build();
                });
        given(nameResolverService.resolveUserFullName(anyLong())).willReturn("山田 太郎");
        given(nameResolverService.resolveUserFullNames(anyCollection())).willReturn(java.util.Map.of());
        given(groupSummaryResolver.resolve(anyList())).willReturn(java.util.Map.of());
        given(slotRepository.findById(any())).willAnswer(inv -> Optional.ofNullable(slots.get(inv.getArgument(0))));
        given(slotRepository.findAllById(anyIterable())).willAnswer(inv -> {
            List<ReservationSlotEntity> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                ReservationSlotEntity s = slots.get(id);
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        });
        given(lineRepository.findById(any())).willReturn(Optional.empty());
        given(lineRepository.findAllById(anyIterable())).willReturn(List.of());
        given(reservationRepository.save(any(ReservationEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(reservationRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
        given(reservationRepository.findAllById(anyIterable())).willAnswer(inv -> {
            List<ReservationEntity> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                ReservationEntity r = reservations.get(id);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        });
        given(reservationPolicyService.getOrDefault(anyLong()))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).build());
        given(reservationPolicyService.resolveApprovalMode(anyLong(), any())).willReturn(ApprovalMode.MANUAL);
        given(slotService.getSlotEntity(anyLong()))
                .willAnswer(inv -> slots.get((Long) inv.getArgument(0)));
    }

    private ReservationService buildService(Clock clock) {
        return new ReservationService(
                reservationRepository, slotRepository, lineRepository, slotService, reservationMapper,
                nameResolverService, eventPublisher, accessControlService, viewAccessGuard,
                reservationPolicyService, blockedTimeRepository, recurringBlockedTimeRepository,
                new ReservationUnavailabilityChecker(), groupSummaryResolver,
                mock(ReservationWaitlistService.class), mock(ReservationCreateRateLimiter.class), clock);
    }

    // ────────────────────────────────────────────────────────────
    // フィクスチャ
    // ────────────────────────────────────────────────────────────

    private ReservationSlotEntity seedSlot(Long slotId, LocalDate date) {
        ReservationSlotEntity s = ReservationSlotEntity.builder()
                .teamId(TEAM_ID).lineId(LINE_ID).title("レッスン")
                .slotDate(date).startTime(START).endTime(END)
                .capacity(1).bookedCount(1).slotStatus(SlotStatus.FULL)
                .build();
        setField(s, "id", slotId);
        slots.put(slotId, s);
        return s;
    }

    private ReservationEntity seedReservation(
            Long id, Long teamId, Long userId, Long slotId, UUID seriesId, ReservationStatus status) {
        ReservationEntity r = ReservationEntity.builder()
                .teamId(teamId).userId(userId).lineId(LINE_ID)
                .reservationSlotId(slotId).status(status)
                .recurringSeriesId(seriesId)
                .bookedAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .build();
        setField(r, "id", id);
        reservations.put(id, r);
        return r;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            java.lang.reflect.Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-7: THIS_ONLY / THIS_AND_FOLLOWING
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-7: THIS_ONLY（既定・scope 省略）は series でも当該1件しかキャンセルしない")
    void 当該回のみキャンセル() {
        seedSlot(8001L, BASE_DATE.minusWeeks(2));
        seedSlot(8002L, BASE_DATE);
        seedSlot(8003L, BASE_DATE.plusWeeks(1));
        ReservationEntity target = seedReservation(101L, TEAM_ID, USER_ID, 8002L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity future = seedReservation(102L, TEAM_ID, USER_ID, 8003L, SERIES_ID, ReservationStatus.CONFIRMED);
        given(reservationRepository.findByIdAndUserId(101L, USER_ID)).willReturn(Optional.of(target));

        ReservationResponse response = service.cancelByUser(
                USER_ID, 101L, new CancelReservationRequest("予定変更", null));

        assertThat(target.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(future.getStatus())
                .as("scope 省略は従来挙動（以降の回に手を出さない）")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(response.getRecurringCancel())
                .as("THIS_ONLY では series 明細を返さない（既存契約不変）")
                .isNull();
        verify(reservationRepository, never()).findByRecurringSeriesIdAndUserIdOrderById(any(), anyLong());
    }

    @Test
    @DisplayName("AC-5-7: THIS_AND_FOLLOWING は当該日より後の回をキャンセルし、過去回は不変")
    void 以降すべてキャンセルし過去回は不変() {
        seedSlot(8001L, BASE_DATE.minusWeeks(2));
        seedSlot(8002L, BASE_DATE);
        seedSlot(8003L, BASE_DATE.plusWeeks(1));
        seedSlot(8004L, BASE_DATE.plusWeeks(2));
        ReservationEntity past = seedReservation(100L, TEAM_ID, USER_ID, 8001L, SERIES_ID, ReservationStatus.COMPLETED);
        ReservationEntity target = seedReservation(101L, TEAM_ID, USER_ID, 8002L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity next1 = seedReservation(102L, TEAM_ID, USER_ID, 8003L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity next2 = seedReservation(103L, TEAM_ID, USER_ID, 8004L, SERIES_ID, ReservationStatus.PENDING);
        given(reservationRepository.findByIdAndUserId(101L, USER_ID)).willReturn(Optional.of(target));
        given(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(SERIES_ID, USER_ID))
                .willReturn(List.of(past, target, next1, next2));

        ReservationResponse response = service.cancelByUser(USER_ID, 101L,
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(target.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(next1.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(next2.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(past.getStatus())
                .as("当該日より前の回（来店済み）は絶対に書き換えない")
                .isEqualTo(ReservationStatus.COMPLETED);

        assertThat(response.getRecurringCancel()).isNotNull();
        assertThat(response.getRecurringCancel().seriesId()).isEqualTo(SERIES_ID);
        assertThat(response.getRecurringCancel().cancelledCount())
                .as("当該回＋以降2回 = 3件")
                .isEqualTo(3);
        assertThat(response.getRecurringCancel().skippedWeeks()).isEmpty();
        // 枠復帰は decrementAndReopen を必ず経由する（キャンセル待ち通知の唯一の統合点）
        verify(slotService).decrementAndReopen(slots.get(8003L));
        verify(slotService).decrementAndReopen(slots.get(8004L));
        verify(slotService, never()).decrementAndReopen(slots.get(8001L));
    }

    @Test
    @DisplayName("AC-5-7: 締切判定は行ごとに（行のチームのポリシーで）適用され、超過回はスキップ明細に載る")
    void 締切判定は行ごとに適用される() {
        // 週次 series は「後の回ほど締切に余裕がある」ため、起点が締切内なら以降も自動的に締切内になる。
        // すなわち起点と同じポリシーを使い回す実装では締切スキップは<b>構造的に起こらない</b>。
        // それでも AC-5-7 は「既存のキャンセル検証を各行に適用する」ことを要求しており、
        // 「1 回だけ判定して全行に流用する」実装は要件を満たさない。
        // その差を暴くため、series 内に別チーム（締切 1 年＝キャンセル不可）の行を混ぜ、
        // 行ごとにポリシーを引いているかを観測する（データ異常時の防御としても正しい挙動）。
        service = buildService(Clock.fixed(
                LocalDateTime.of(2026, 6, 1, 12, 0).toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).cancelDeadlineHours(24).build());
        given(reservationPolicyService.getOrDefault(OTHER_TEAM_ID))
                .willReturn(ReservationPolicyEntity.builder()
                        .teamId(OTHER_TEAM_ID).cancelDeadlineHours(8760).build());

        seedSlot(8020L, BASE_DATE);                 // 起点（TEAM_ID・締切24h → キャンセル可）
        seedSlot(8021L, BASE_DATE.plusWeeks(1));    // 別チーム・締切1年 → 超過でスキップ
        seedSlot(8022L, BASE_DATE.plusWeeks(2));    // TEAM_ID・締切24h → キャンセル可
        ReservationEntity target = seedReservation(301L, TEAM_ID, USER_ID, 8020L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity tooSoon =
                seedReservation(302L, OTHER_TEAM_ID, USER_ID, 8021L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity far = seedReservation(303L, TEAM_ID, USER_ID, 8022L, SERIES_ID, ReservationStatus.CONFIRMED);
        given(reservationRepository.findByIdAndUserId(301L, USER_ID)).willReturn(Optional.of(target));
        given(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(SERIES_ID, USER_ID))
                .willReturn(List.of(target, tooSoon, far));

        ReservationResponse response = service.cancelByUser(USER_ID, 301L,
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(target.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(far.getStatus())
                .as("締切内の回は（途中の1回がスキップされても）キャンセルされる")
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(tooSoon.getStatus())
                .as("締切を過ぎた回はキャンセルせず据え置く（例外にもしない）")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(response.getRecurringCancel().cancelledCount()).isEqualTo(2);
        assertThat(response.getRecurringCancel().skippedWeeks())
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.date()).isEqualTo(BASE_DATE.plusWeeks(1));
                    assertThat(w.reason()).isEqualTo(RecurringWeekSkipReason.CANCEL_DEADLINE_PASSED);
                });
    }

    @Test
    @DisplayName("AC-5-7: キャンセル不能な状態（CANCELLED 済み）の回はスキップ明細に載る")
    void キャンセル不能な回はスキップする() {
        seedSlot(8030L, BASE_DATE);
        seedSlot(8031L, BASE_DATE.plusWeeks(1));
        ReservationEntity target = seedReservation(401L, TEAM_ID, USER_ID, 8030L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity already = seedReservation(402L, TEAM_ID, USER_ID, 8031L, SERIES_ID, ReservationStatus.CANCELLED);
        given(reservationRepository.findByIdAndUserId(401L, USER_ID)).willReturn(Optional.of(target));
        given(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(SERIES_ID, USER_ID))
                .willReturn(List.of(target, already));

        ReservationResponse response = service.cancelByUser(USER_ID, 401L,
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(response.getRecurringCancel().cancelledCount()).isEqualTo(1);
        assertThat(response.getRecurringCancel().skippedWeeks())
                .singleElement()
                .satisfies(w -> assertThat(w.reason())
                        .isEqualTo(RecurringWeekSkipReason.NOT_CANCELLABLE));
        verify(slotService, never()).decrementAndReopen(slots.get(8031L));
    }

    // ────────────────────────────────────────────────────────────
    // 検分 MUST④: 起点回の締切超過も例外にせずスキップする
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MUST④: 起点回が締切超過でも 400 にせず、以降の回はキャンセルされる")
    void 起点回が締切超過でも以降はキャンセルされる() {
        // 「今日の回は締切だが来週以降はまとめて消したい」は会員の当然の操作。
        // 起点回だけ例外にすると THIS_AND_FOLLOWING 全体が 400 で失敗し、その操作ができない。
        service = buildService(Clock.fixed(
                LocalDateTime.of(2026, 6, 16, 12, 0).toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).cancelDeadlineHours(24).build());

        seedSlot(8100L, BASE_DATE);                 // 今日 19:00 → 24h 締切を過ぎている
        seedSlot(8101L, BASE_DATE.plusWeeks(1));    // 来週 → 締切内
        seedSlot(8102L, BASE_DATE.plusWeeks(2));    // 再来週 → 締切内
        ReservationEntity target = seedReservation(1101L, TEAM_ID, USER_ID, 8100L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity next1 = seedReservation(1102L, TEAM_ID, USER_ID, 8101L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity next2 = seedReservation(1103L, TEAM_ID, USER_ID, 8102L, SERIES_ID, ReservationStatus.CONFIRMED);
        given(reservationRepository.findByIdAndUserId(1101L, USER_ID)).willReturn(Optional.of(target));
        given(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(SERIES_ID, USER_ID))
                .willReturn(List.of(target, next1, next2));

        ReservationResponse response = service.cancelByUser(USER_ID, 1101L,
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(target.getStatus())
                .as("起点回は締切超過なのでキャンセルされない（据え置き）")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(next1.getStatus()).as("来週以降はキャンセルされる").isEqualTo(ReservationStatus.CANCELLED);
        assertThat(next2.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.getRecurringCancel()).isNotNull();
        assertThat(response.getRecurringCancel().cancelledCount()).isEqualTo(2);
        assertThat(response.getRecurringCancel().skippedWeeks())
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.date()).isEqualTo(BASE_DATE);
                    assertThat(w.reason()).isEqualTo(RecurringWeekSkipReason.CANCEL_DEADLINE_PASSED);
                    assertThat(w.reservationId()).isEqualTo(1101L);
                });
        // 起点回がキャンセルされていないので会員キャンセル通知も飛ばさない（嘘の通知を出さない）
        verify(eventPublisher, never()).publishEvent(any(ReservationCancelledByMemberEvent.class));
    }

    @Test
    @DisplayName("MUST④: 全回が締切超過でもエラーにせず 0 件の明細を返す（AC-5-13 と同じ思想）")
    void 全回締切超過でもエラーにしない() {
        service = buildService(Clock.fixed(
                LocalDateTime.of(2026, 7, 20, 12, 0).toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).cancelDeadlineHours(24).build());

        seedSlot(8110L, BASE_DATE);
        seedSlot(8111L, BASE_DATE.plusWeeks(1));
        ReservationEntity target = seedReservation(1201L, TEAM_ID, USER_ID, 8110L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity next1 = seedReservation(1202L, TEAM_ID, USER_ID, 8111L, SERIES_ID, ReservationStatus.CONFIRMED);
        given(reservationRepository.findByIdAndUserId(1201L, USER_ID)).willReturn(Optional.of(target));
        given(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(SERIES_ID, USER_ID))
                .willReturn(List.of(target, next1));

        ReservationResponse response = service.cancelByUser(USER_ID, 1201L,
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(response.getRecurringCancel().cancelledCount())
                .as("0 件でも例外にせず明細を返す")
                .isZero();
        assertThat(response.getRecurringCancel().skippedWeeks()).hasSize(2);
        assertThat(target.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(next1.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(slotService, never()).decrementAndReopen(any());
    }

    @Test
    @DisplayName("MUST④: THIS_ONLY（従来経路）では締切超過は従来どおり 400 のまま（挙動不変）")
    void 従来経路の締切超過は400のまま() {
        service = buildService(Clock.fixed(
                LocalDateTime.of(2026, 6, 16, 12, 0).toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).cancelDeadlineHours(24).build());

        seedSlot(8120L, BASE_DATE);
        ReservationEntity target = seedReservation(1301L, TEAM_ID, USER_ID, 8120L, SERIES_ID, ReservationStatus.CONFIRMED);
        given(reservationRepository.findByIdAndUserId(1301L, USER_ID)).willReturn(Optional.of(target));

        assertThatThrownBy(() -> service.cancelByUser(USER_ID, 1301L,
                new CancelReservationRequest("この回のみ", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .as("単票キャンセルの締切超過は従来どおり 400（挙動を変えない）")
                .isEqualTo(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        assertThat(target.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-8: IDOR — 他人の行は絶対に触らない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-8: series に他人の行が混ざっていても自分の予約以外はキャンセルされない")
    void 他人の行はキャンセルされない() {
        seedSlot(8040L, BASE_DATE);
        seedSlot(8041L, BASE_DATE.plusWeeks(1));
        ReservationEntity mine = seedReservation(501L, TEAM_ID, USER_ID, 8040L, SERIES_ID, ReservationStatus.CONFIRMED);
        ReservationEntity theirs =
                seedReservation(502L, TEAM_ID, OTHER_USER_ID, 8041L, SERIES_ID, ReservationStatus.CONFIRMED);
        given(reservationRepository.findByIdAndUserId(501L, USER_ID)).willReturn(Optional.of(mine));
        // ユーザースコープの finder は本人の行しか返さない（これが構造的な防御）
        given(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(SERIES_ID, USER_ID))
                .willReturn(List.of(mine));

        ReservationResponse response = service.cancelByUser(USER_ID, 501L,
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(theirs.getStatus())
                .as("他人の予約は series ID を共有していてもキャンセルされない")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(response.getRecurringCancel().cancelledCount()).isEqualTo(1);
        // 実装が userId 無しの finder（存在しない）や findAll 相当に退行していないことを固定する
        verify(reservationRepository).findByRecurringSeriesIdAndUserIdOrderById(SERIES_ID, USER_ID);
        verify(reservationRepository, never()).findAll();
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-9: scope=SERIES の一括承認
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-9: scope=SERIES で series 内 PENDING を一括 CONFIRMED にする")
    void シリーズ一括承認() {
        seedSlot(8050L, BASE_DATE);
        seedSlot(8051L, BASE_DATE.plusWeeks(1));
        seedSlot(8052L, BASE_DATE.plusWeeks(2));
        ReservationEntity target = seedReservation(601L, TEAM_ID, USER_ID, 8050L, SERIES_ID, ReservationStatus.PENDING);
        ReservationEntity p2 = seedReservation(602L, TEAM_ID, USER_ID, 8051L, SERIES_ID, ReservationStatus.PENDING);
        ReservationEntity p3 = seedReservation(603L, TEAM_ID, USER_ID, 8052L, SERIES_ID, ReservationStatus.PENDING);
        given(reservationRepository.findByIdAndTeamId(601L, TEAM_ID)).willReturn(Optional.of(target));
        given(reservationRepository.findByRecurringSeriesIdAndTeamIdOrderById(SERIES_ID, TEAM_ID))
                .willReturn(List.of(target, p2, p3));

        ReservationResponse response =
                service.confirmReservation(TEAM_ID, 601L, ReservationConfirmScope.SERIES);

        assertThat(List.of(target, p2, p3))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED));
        assertThat(response.getRecurringConfirm()).isNotNull();
        assertThat(response.getRecurringConfirm().confirmedCount()).isEqualTo(3);
        assertThat(response.getRecurringConfirm().skippedWeeks()).isEmpty();
    }

    @Test
    @DisplayName("AC-5-9: PENDING でない回はスキップ明細（NOT_PENDING）に載る")
    void 承認対象外はスキップする() {
        seedSlot(8060L, BASE_DATE);
        seedSlot(8061L, BASE_DATE.plusWeeks(1));
        ReservationEntity target = seedReservation(701L, TEAM_ID, USER_ID, 8060L, SERIES_ID, ReservationStatus.PENDING);
        ReservationEntity done = seedReservation(702L, TEAM_ID, USER_ID, 8061L, SERIES_ID, ReservationStatus.CONFIRMED);
        given(reservationRepository.findByIdAndTeamId(701L, TEAM_ID)).willReturn(Optional.of(target));
        given(reservationRepository.findByRecurringSeriesIdAndTeamIdOrderById(SERIES_ID, TEAM_ID))
                .willReturn(List.of(target, done));

        ReservationResponse response =
                service.confirmReservation(TEAM_ID, 701L, ReservationConfirmScope.SERIES);

        assertThat(response.getRecurringConfirm().confirmedCount()).isEqualTo(1);
        assertThat(response.getRecurringConfirm().skippedWeeks())
                .singleElement()
                .satisfies(w -> assertThat(w.reason())
                        .isEqualTo(RecurringWeekSkipReason.NOT_PENDING));
    }

    @Test
    @DisplayName("AC-5-9: 一括承認はチームスコープの finder を使う（他チームの行を掴まない）")
    void 一括承認はチームスコープで解決する() {
        seedSlot(8070L, BASE_DATE);
        ReservationEntity target = seedReservation(801L, TEAM_ID, USER_ID, 8070L, SERIES_ID, ReservationStatus.PENDING);
        ReservationEntity crossTeam =
                seedReservation(802L, OTHER_TEAM_ID, USER_ID, 8070L, SERIES_ID, ReservationStatus.PENDING);
        given(reservationRepository.findByIdAndTeamId(801L, TEAM_ID)).willReturn(Optional.of(target));
        // チームスコープ finder は当該チームの行のみを返す（これが構造的な防御）
        given(reservationRepository.findByRecurringSeriesIdAndTeamIdOrderById(SERIES_ID, TEAM_ID))
                .willReturn(List.of(target));

        ReservationResponse response =
                service.confirmReservation(TEAM_ID, 801L, ReservationConfirmScope.SERIES);

        assertThat(crossTeam.getStatus())
                .as("別チームの行は series を共有していても承認されない（テナント境界越え禁止）")
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(response.getRecurringConfirm().confirmedCount()).isEqualTo(1);
        verify(reservationRepository).findByRecurringSeriesIdAndTeamIdOrderById(SERIES_ID, TEAM_ID);
        verify(reservationRepository, never()).findByRecurringSeriesIdAndUserIdOrderById(any(), anyLong());
    }

    @Test
    @DisplayName("AC-5-9: scope 省略（単票承認）では series 明細を返さず他の回に手を出さない")
    void 単票承認は従来挙動() {
        seedSlot(8080L, BASE_DATE);
        seedSlot(8081L, BASE_DATE.plusWeeks(1));
        ReservationEntity target = seedReservation(901L, TEAM_ID, USER_ID, 8080L, SERIES_ID, ReservationStatus.PENDING);
        ReservationEntity other = seedReservation(902L, TEAM_ID, USER_ID, 8081L, SERIES_ID, ReservationStatus.PENDING);
        given(reservationRepository.findByIdAndTeamId(901L, TEAM_ID)).willReturn(Optional.of(target));

        ReservationResponse response = service.confirmReservation(TEAM_ID, 901L);

        assertThat(target.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(other.getStatus())
                .as("単票承認は従来どおり1件だけ")
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(response.getRecurringConfirm()).isNull();
        verify(reservationRepository, never()).findByRecurringSeriesIdAndTeamIdOrderById(any(), anyLong());
    }

    @Test
    @DisplayName("AC-5-9: series に属さない予約へ scope=SERIES を指定しても1件だけ承認される（無害）")
    void 単発予約へのSERIES指定は無害() {
        seedSlot(8090L, BASE_DATE);
        ReservationEntity single = seedReservation(1001L, TEAM_ID, USER_ID, 8090L, null, ReservationStatus.PENDING);
        given(reservationRepository.findByIdAndTeamId(1001L, TEAM_ID)).willReturn(Optional.of(single));

        ReservationResponse response =
                service.confirmReservation(TEAM_ID, 1001L, ReservationConfirmScope.SERIES);

        assertThat(single.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(response.getRecurringConfirm()).isNull();
        verify(reservationRepository, never()).findByRecurringSeriesIdAndTeamIdOrderById(any(), eq(TEAM_ID));
    }
}
