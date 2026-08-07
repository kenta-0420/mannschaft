package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 定期予約オーケストレーターのテスト（F03.4.5 §6.2 W2-5）。
 *
 * <p>受け入れ条件の対応:</p>
 * <ul>
 *   <li><b>AC-5-2</b>: 成立した全行が同一 {@code recurring_series_id} を持つ</li>
 *   <li><b>AC-5-3</b>: {@code repeatWeeks=13} は {@code RESERVATION_054}・12 は成功（境界）</li>
 *   <li><b>AC-5-4</b>: 起点週が確保できなければ全体が失敗（2週目以降だけ成立させない）</li>
 *   <li><b>AC-5-5</b>: 2週目以降の失敗はスキップ扱いで、成立分は残る</li>
 *   <li><b>AC-5-6</b>: 確保の順序が {@code slot_date} 昇順（デッドロック回避のロック順序）</li>
 *   <li><b>AC-5-11</b>: 1 series = レートリミット 1 消費（週数ぶん消費しない）</li>
 *   <li><b>AC-5-13</b>: 追加週が全スキップなら series を発行しない（起点1件は成立して 2xx）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("定期予約 オーケストレーターテスト（F03.4.5 §6.2 / AC-5-2〜6・11・13）")
class ReservationRecurringServiceTest {

    private static final Long TEAM_ID = 5001L;
    private static final Long USER_ID = 5002L;
    private static final Long LINE_ID = 5003L;
    private static final Long BASE_SLOT_ID = 7000L;

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 6, 2);
    private static final LocalTime START = LocalTime.of(19, 0);
    private static final LocalTime END = LocalTime.of(20, 0);

    private static final Clock FIXED_CLOCK =
            Clock.fixed(BASE_DATE.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    @Mock
    private ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private ReservationCreateRateLimiter createRateLimiter;
    @Mock
    private ReservationService reservationService;
    @Mock
    private ReservationSlotService slotService;
    @Mock
    private ReservationRecurringSlotResolver slotResolver;

    private ReservationRecurringService service;

    /** 作成された予約 ID を採番するカウンタ（呼び出し順の観測にも使う）。 */
    private final AtomicLong createdIdSeq = new AtomicLong(1L);
    /** {@code createReservationForSeries} に渡された slotId を呼び出し順に記録する。 */
    private final List<Long> createdSlotIdsInOrder = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ReservationRecurringService(
                viewAccessGuard, createRateLimiter, reservationService, slotService,
                slotResolver, FIXED_CLOCK);

        given(slotService.getSlotEntity(TEAM_ID, BASE_SLOT_ID)).willReturn(baseSlot());

        // 予約作成は「成功して ID を採番する」ふるまいに固定する（呼び出し順も記録）。
        willAnswer(inv -> {
            CreateReservationRequest req = inv.getArgument(2);
            createdSlotIdsInOrder.add(req.getReservationSlotId());
            return responseWithId(createdIdSeq.getAndIncrement());
        }).given(reservationService).createReservationForSeries(anyLong(), anyLong(), any(), any());
    }

    private static ReservationResponse responseWithId(Long id) {
        return ReservationResponse.builder().id(id).build();
    }

    private ReservationSlotEntity baseSlot() {
        ReservationSlotEntity s = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .lineId(LINE_ID)
                .title("レッスン")
                .slotDate(BASE_DATE)
                .startTime(START)
                .endTime(END)
                .capacity(1)
                .bookedCount(0)
                .slotStatus(SlotStatus.AVAILABLE)
                .build();
        setField(s, "id", BASE_SLOT_ID);
        return s;
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

    private CreateReservationRequest request(Integer repeatWeeks) {
        return new CreateReservationRequest(BASE_SLOT_ID, LINE_ID, "備考", repeatWeeks);
    }

    /** 2週目以降すべて予約可能な解決結果を返すようスタブする。 */
    private void givenAllBookable(int repeatWeeks) {
        List<ReservationRecurringSlotResolver.WeekCandidate> candidates = new ArrayList<>();
        for (int k = 1; k < repeatWeeks; k++) {
            candidates.add(new ReservationRecurringSlotResolver.WeekCandidate(
                    BASE_DATE.plusWeeks(k), BASE_SLOT_ID + k, null));
        }
        given(slotResolver.resolve(anyLong(), anyLong(), any(), anyInt())).willReturn(candidates);
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-2: 全行が同一 series
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-2: 成立した全行に同一の recurring_series_id が渡される")
    void 全行が同一series() {
        givenAllBookable(4);

        ReservationResponse response = service.createRecurring(TEAM_ID, USER_ID, request(4));

        ArgumentCaptor<UUID> seriesCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(reservationService, times(4))
                .createReservationForSeries(eq(TEAM_ID), eq(USER_ID), any(), seriesCaptor.capture());
        assertThat(seriesCaptor.getAllValues())
                .as("起点週を含む4回すべてに同一の series ID が渡ること")
                .doesNotContainNull()
                .hasSize(4)
                .containsOnly(seriesCaptor.getAllValues().get(0));
        assertThat(response.getRecurring()).isNotNull();
        assertThat(response.getRecurring().seriesId())
                .as("レスポンスの seriesId も同一である")
                .isEqualTo(seriesCaptor.getAllValues().get(0));
        assertThat(response.getRecurring().createdCount()).isEqualTo(4);
        assertThat(response.getRecurring().skippedCount()).isZero();
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-3: 上限 12 / 13 は 400=RESERVATION_054
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-3(境界): repeatWeeks=12 は成功する")
    void 十二週は成功する() {
        givenAllBookable(12);

        ReservationResponse response = service.createRecurring(TEAM_ID, USER_ID, request(12));

        assertThat(response.getRecurring().createdCount()).isEqualTo(12);
        verify(reservationService, times(12))
                .createReservationForSeries(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("AC-5-3(境界): repeatWeeks=13 は RESERVATION_054 で拒否し、予約を1件も作らない")
    void 十三週は拒否する() {
        assertThatThrownBy(() -> service.createRecurring(TEAM_ID, USER_ID, request(13)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RECURRING_RESERVATION_LIMIT_EXCEEDED);

        verify(reservationService, never())
                .createReservationForSeries(anyLong(), anyLong(), any(), any());
        verify(createRateLimiter, never()).assertNotRateLimited(anyLong());
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-4: 起点週が確保できなければ全体失敗
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-4: 起点週が満席なら例外を伝播し、2週目以降を一切作らない")
    void 起点週失敗で全体失敗() {
        givenAllBookable(4);
        // 1 回目（起点週）だけ失敗させる
        willThrow(new BusinessException(ReservationErrorCode.SLOT_FULL))
                .given(reservationService)
                .createReservationForSeries(anyLong(), anyLong(), any(), any());

        assertThatThrownBy(() -> service.createRecurring(TEAM_ID, USER_ID, request(4)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.SLOT_FULL);

        verify(reservationService, times(1))
                .createReservationForSeries(anyLong(), anyLong(), any(), any());
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-5: 2週目以降の失敗はスキップ・成立分は残る
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-5: 3週目の確保失敗はスキップ扱いで、4週目以降の作成を止めない")
    void 途中失敗でも後続を続行する() {
        givenAllBookable(4);
        // 起点(7000) OK / 2週目(7001) OK / 3週目(7002) 失敗 / 4週目(7003) OK
        willAnswer(inv -> {
            CreateReservationRequest req = inv.getArgument(2);
            createdSlotIdsInOrder.add(req.getReservationSlotId());
            if (req.getReservationSlotId().equals(BASE_SLOT_ID + 2)) {
                throw new BusinessException(ReservationErrorCode.SLOT_FULL);
            }
            return responseWithId(createdIdSeq.getAndIncrement());
        }).given(reservationService).createReservationForSeries(anyLong(), anyLong(), any(), any());

        ReservationResponse response = service.createRecurring(TEAM_ID, USER_ID, request(4));

        assertThat(response.getRecurring().createdCount())
                .as("3週目だけ落ちても残り3件は成立する（1件の失敗が全件を巻き込まない）")
                .isEqualTo(3);
        assertThat(response.getRecurring().skippedCount()).isEqualTo(1);
        assertThat(response.getRecurring().skippedWeeks())
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.date()).isEqualTo(BASE_DATE.plusWeeks(2));
                    assertThat(w.reason()).isEqualTo(RecurringWeekSkipReason.FULL);
                });
        assertThat(createdSlotIdsInOrder)
                .as("4週目の作成が試行されていること（3週目の失敗で打ち切っていない）")
                .contains(BASE_SLOT_ID + 3);
    }

    @Test
    @DisplayName("AC-5-5: 解決時点のスキップ（枠なし/BLOCKED）は明細に理由がそのまま載る")
    void 解決時点のスキップ理由が明細に載る() {
        given(slotResolver.resolve(anyLong(), anyLong(), any(), anyInt())).willReturn(List.of(
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(1), null, RecurringWeekSkipReason.NOT_GENERATED),
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(2), BASE_SLOT_ID + 2, RecurringWeekSkipReason.BLOCKED),
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(3), BASE_SLOT_ID + 3, null)));

        ReservationResponse response = service.createRecurring(TEAM_ID, USER_ID, request(4));

        assertThat(response.getRecurring().createdCount()).isEqualTo(2);
        assertThat(response.getRecurring().skippedWeeks())
                .extracting(ReservationResponse.RecurringWeekOutcomeDto::reason)
                .containsExactly(
                        RecurringWeekSkipReason.NOT_GENERATED,
                        RecurringWeekSkipReason.BLOCKED);
        // スキップ週の予約作成は試行しない（無駄な確保 UPDATE を打たない）
        assertThat(createdSlotIdsInOrder)
                .containsExactly(BASE_SLOT_ID, BASE_SLOT_ID + 3);
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-6: ロック順序（slot_date 昇順）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-6: 解決結果が日付降順で来ても、確保は slot_date 昇順で行う")
    void 確保はslot_date昇順で行う() {
        // 解決結果をあえて降順で返す（実装が「取得順そのまま」に依存していないことを暴く）
        given(slotResolver.resolve(anyLong(), anyLong(), any(), anyInt())).willReturn(List.of(
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(3), BASE_SLOT_ID + 3, null),
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(1), BASE_SLOT_ID + 1, null),
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(2), BASE_SLOT_ID + 2, null)));

        service.createRecurring(TEAM_ID, USER_ID, request(4));

        assertThat(createdSlotIdsInOrder)
                .as("起点 → 日付昇順で確保すること（デッドロック回避のロック順序）")
                .containsExactly(BASE_SLOT_ID, BASE_SLOT_ID + 1, BASE_SLOT_ID + 2, BASE_SLOT_ID + 3);
    }

    @Test
    @DisplayName("AC-5-6: 成立明細も日付昇順で返る")
    void 成立明細は日付昇順() {
        givenAllBookable(4);

        ReservationResponse response = service.createRecurring(TEAM_ID, USER_ID, request(4));

        assertThat(response.getRecurring().createdWeeks())
                .extracting(ReservationResponse.RecurringWeekOutcomeDto::date)
                .containsExactly(BASE_DATE, BASE_DATE.plusWeeks(1),
                        BASE_DATE.plusWeeks(2), BASE_DATE.plusWeeks(3));
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-11: 1 series = 1 消費
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-11: 12週の定期予約でもレートリミット消費は1回・認可ゲートも1回")
    void 一シリーズ一消費() {
        givenAllBookable(12);

        service.createRecurring(TEAM_ID, USER_ID, request(12));

        verify(createRateLimiter, times(1)).assertNotRateLimited(USER_ID);
        verify(viewAccessGuard, times(1)).assertCanView(TEAM_ID, USER_ID);
        // 週ごとの作成経路はゲートもリミッタも持たない前提。オーケストレーターが 1 回だけ適用する。
        verify(reservationService, never()).createReservation(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("AC-5-11: 認可NGならレートリミットを消費せず 403 を返す（403のはずが429になる事故を防ぐ）")
    void 認可NGでは消費しない() {
        givenAllBookable(4);
        willThrow(new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                .given(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

        assertThatThrownBy(() -> service.createRecurring(TEAM_ID, USER_ID, request(4)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);

        verify(createRateLimiter, never()).assertNotRateLimited(anyLong());
    }

    @Test
    @DisplayName("AC-5-11: レートリミット超過なら 429 を返し、予約を1件も作らない")
    void レートリミット超過では作らない() {
        givenAllBookable(4);
        willThrow(new BusinessException(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED))
                .given(createRateLimiter).assertNotRateLimited(USER_ID);

        assertThatThrownBy(() -> service.createRecurring(TEAM_ID, USER_ID, request(4)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_CREATE_RATE_LIMITED);

        verify(reservationService, never())
                .createReservationForSeries(anyLong(), anyLong(), any(), any());
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-13: 追加週が全スキップなら series を発行しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-13: 2週目以降が全スキップなら series を解除し、明細つきで成功を返す")
    void 全スキップならseriesを発行しない() {
        given(slotResolver.resolve(anyLong(), anyLong(), any(), anyInt())).willReturn(List.of(
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(1), null, RecurringWeekSkipReason.NOT_GENERATED),
                new ReservationRecurringSlotResolver.WeekCandidate(
                        BASE_DATE.plusWeeks(2), null, RecurringWeekSkipReason.NOT_GENERATED)));

        ReservationResponse response = service.createRecurring(TEAM_ID, USER_ID, request(3));

        assertThat(response.getId()).as("起点週の予約は成立している（エラーにしない）").isNotNull();
        assertThat(response.getRecurring()).isNotNull();
        assertThat(response.getRecurring().createdCount()).isEqualTo(1);
        assertThat(response.getRecurring().skippedCount()).isEqualTo(2);
        assertThat(response.getRecurring().seriesId())
                .as("1行だけの series は発行しない（単発予約と区別する意味がない）")
                .isNull();
        // 起点週の series 所属を実際に解除している（DB 上も NULL に戻す）
        verify(reservationService, times(1)).clearRecurringSeries(anyLong());
    }

    @Test
    @DisplayName("AC-5-13: 2件以上成立した場合は series を解除しない")
    void 二件以上ならseriesを維持する() {
        givenAllBookable(2);

        ReservationResponse response = service.createRecurring(TEAM_ID, USER_ID, request(2));

        assertThat(response.getRecurring().seriesId()).isNotNull();
        verify(reservationService, never()).clearRecurringSeries(anyLong());
    }
}
