package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.RecurringWeekSkipReason;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 定期予約の週次枠解決テスト（F03.4.5 §6.2 W2-5）。
 *
 * <p>受け入れ条件の対応:</p>
 * <ul>
 *   <li><b>AC-5-1</b>: {@code repeatWeeks=4} は<b>起点週を含む 4 週</b>（＝解決対象は 2〜4 週目の 3 件）。
 *       FULL / 枠が存在しない / BLOCKED はスキップ理由つきで返る</li>
 *   <li><b>AC-5-10</b>: 12 週でも枠解決のクエリ本数が 2 週のときと同じ（範囲検索 1 回・N+1 なし）</li>
 *   <li><b>AC-5-12</b>: 定期予約不可枠に当たる週は {@code BLOCKED}（{@code isBlockedByAny} 経由）</li>
 * </ul>
 *
 * <p>判定コア（{@link ReservationUnavailabilityChecker}）は<b>実物</b>を注入する。
 * mock にすると「isBlockedByAny を経由している」という AC-5-12 の要点が
 * スタブの戻り値で偽装できてしまい、判定の実体を検証できない。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("定期予約 週次枠解決テスト（F03.4.5 §6.2 / AC-5-1・AC-5-10・AC-5-12）")
class ReservationRecurringSlotResolverTest {

    private static final Long TEAM_ID = 4001L;
    private static final Long USER_ID = 4002L;
    private static final Long LINE_ID = 4003L;

    /** 2026-06-02 は火曜。週次繰り返しの基準日。 */
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 6, 2);
    private static final LocalTime START = LocalTime.of(19, 0);
    private static final LocalTime END = LocalTime.of(20, 0);

    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationBlockedTimeRepository blockedTimeRepository;
    @Mock
    private ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;
    @Mock
    private ReservationRepository reservationRepository;

    private ReservationRecurringSlotResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReservationRecurringSlotResolver(
                slotRepository, blockedTimeRepository, recurringBlockedTimeRepository,
                new ReservationUnavailabilityChecker(), reservationRepository);
        given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                anyLong(), any(), any())).willReturn(List.of());
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(anyLong())).willReturn(List.of());
        given(reservationRepository.findSlotIdsAlreadyReservedByUser(anyLong(), any(), any()))
                .willReturn(List.of());
    }

    /** 起点枠（火曜 19:00-20:00・ライン軸）。 */
    private ReservationSlotEntity baseSlot() {
        return slot(9000L, BASE_DATE, SlotStatus.AVAILABLE, 1, 0);
    }

    private ReservationSlotEntity slot(
            Long id, LocalDate date, SlotStatus status, int capacity, int booked) {
        ReservationSlotEntity s = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .lineId(LINE_ID)
                .title("レッスン")
                .slotDate(date)
                .startTime(START)
                .endTime(END)
                .capacity(capacity)
                .bookedCount(booked)
                .slotStatus(status)
                .build();
        // ID は永続化で採番されるため、テストでは reflection で埋める（Entity に setter を生やさない）。
        setId(s, id);
        return s;
    }

    private static void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f = findField(entity.getClass(), "id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-1: 起点週を含む数え方＋スキップ明細
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-1: repeatWeeks=4 は起点週を含む4週 → 解決対象は2〜4週目の3件のみ")
    void 起点週を含めて数える() {
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(List.of(
                        slot(9001L, BASE_DATE.plusWeeks(1), SlotStatus.AVAILABLE, 1, 0),
                        slot(9002L, BASE_DATE.plusWeeks(2), SlotStatus.AVAILABLE, 1, 0),
                        slot(9003L, BASE_DATE.plusWeeks(3), SlotStatus.AVAILABLE, 1, 0)));

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 4);

        assertThat(result)
                .as("repeatWeeks=4 は「起点＋3週」であり、解決対象は3件（4件ではない）")
                .hasSize(3);
        assertThat(result).extracting(ReservationRecurringSlotResolver.WeekCandidate::date)
                .containsExactly(BASE_DATE.plusWeeks(1), BASE_DATE.plusWeeks(2), BASE_DATE.plusWeeks(3));
        assertThat(result).allSatisfy(c -> assertThat(c.bookable()).isTrue());
    }

    @Test
    @DisplayName("AC-5-1: 枠が無い週は NOT_GENERATED・満席週は FULL でスキップ理由が付く")
    void 枠なしと満席のスキップ理由() {
        // 2週目 = 枠なし（生成 horizon 外）／3週目 = FULL／4週目 = capacity 到達（status は AVAILABLE のまま）
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(List.of(
                        slot(9002L, BASE_DATE.plusWeeks(2), SlotStatus.FULL, 1, 1),
                        slot(9003L, BASE_DATE.plusWeeks(3), SlotStatus.AVAILABLE, 2, 2)));

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 4);

        assertThat(result).hasSize(3);
        assertThat(reasonOf(result, BASE_DATE.plusWeeks(1)))
                .as("枠が生成されていない週は NOT_GENERATED")
                .isEqualTo(RecurringWeekSkipReason.NOT_GENERATED);
        assertThat(reasonOf(result, BASE_DATE.plusWeeks(2)))
                .as("slot_status=FULL は FULL")
                .isEqualTo(RecurringWeekSkipReason.FULL);
        assertThat(reasonOf(result, BASE_DATE.plusWeeks(3)))
                .as("booked_count >= capacity も FULL（status が追いついていない枠を見逃さない）")
                .isEqualTo(RecurringWeekSkipReason.FULL);
    }

    @Test
    @DisplayName("AC-5-1: 受付停止枠は CLOSED・既に自分の予約がある枠は ALREADY_RESERVED")
    void 受付停止と二重予約のスキップ理由() {
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(List.of(
                        slot(9001L, BASE_DATE.plusWeeks(1), SlotStatus.CLOSED, 1, 0),
                        slot(9002L, BASE_DATE.plusWeeks(2), SlotStatus.AVAILABLE, 1, 0)));
        given(reservationRepository.findSlotIdsAlreadyReservedByUser(anyLong(), any(), any()))
                .willReturn(List.of(9002L));

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 3);

        assertThat(reasonOf(result, BASE_DATE.plusWeeks(1)))
                .as("受付停止は FULL に丸めず CLOSED と正直に返す")
                .isEqualTo(RecurringWeekSkipReason.CLOSED);
        assertThat(reasonOf(result, BASE_DATE.plusWeeks(2)))
                .as("既に自分の active 予約がある枠は ALREADY_RESERVED")
                .isEqualTo(RecurringWeekSkipReason.ALREADY_RESERVED);
    }

    @Test
    @DisplayName("AC-5-1: 起点日+7の倍数でない日付の枠は候補にしない（範囲検索の取りこぼしを混ぜない）")
    void 七日の倍数でない枠は除外する() {
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(List.of(
                        // 範囲検索は日付レンジで引くため、曜日違いの枠も混ざって返ってくる
                        slot(9010L, BASE_DATE.plusDays(3), SlotStatus.AVAILABLE, 1, 0),
                        slot(9001L, BASE_DATE.plusWeeks(1), SlotStatus.AVAILABLE, 1, 0)));

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 2);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).date()).isEqualTo(BASE_DATE.plusWeeks(1));
        assertThat(result.get(0).slotId())
                .as("同一曜日の枠だけを採用する（+3日の枠を拾ってはならない）")
                .isEqualTo(9001L);
    }

    @Test
    @DisplayName("AC-5-1: ラインが異なる枠は候補にしない（同一ラインのみ）")
    void 別ラインの枠は除外する() {
        ReservationSlotEntity otherLine = slot(9020L, BASE_DATE.plusWeeks(1), SlotStatus.AVAILABLE, 1, 0);
        setLineId(otherLine, 999L);
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(List.of(otherLine));

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 2);

        assertThat(reasonOf(result, BASE_DATE.plusWeeks(1)))
                .as("別ラインの枠は「同一ラインの枠が無い」= NOT_GENERATED として扱う")
                .isEqualTo(RecurringWeekSkipReason.NOT_GENERATED);
    }

    private static void setLineId(ReservationSlotEntity slot, Long lineId) {
        try {
            java.lang.reflect.Field f = findField(slot.getClass(), "lineId");
            f.setAccessible(true);
            f.set(slot, lineId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-12: 定期予約不可枠は BLOCKED
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-12: 定期予約不可枠に当たる週は BLOCKED（isBlockedByAny 経由の実判定）")
    void 定期不可枠の週はBLOCKED() {
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(List.of(
                        slot(9001L, BASE_DATE.plusWeeks(1), SlotStatus.AVAILABLE, 1, 0),
                        slot(9002L, BASE_DATE.plusWeeks(2), SlotStatus.AVAILABLE, 1, 0)));
        // 火曜 19:00-20:00 をチーム全体でブロックするルール（起点枠と完全に重なる）
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(anyLong()))
                .willReturn(List.of(ReservationRecurringBlockedTimeEntity.builder()
                        .teamId(TEAM_ID)
                        .lineId(null)
                        .dayOfWeek(ReservationDayOfWeek.TUE)
                        .startTime(LocalTime.of(19, 0))
                        .endTime(LocalTime.of(20, 0))
                        .reason("研修")
                        .isPublic(true)
                        .isActive(true)
                        .build()));

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 3);

        assertThat(result)
                .as("毎週火曜19時がブロックされているので、全ての週が BLOCKED になる")
                .allSatisfy(c -> assertThat(c.skipReason()).isEqualTo(RecurringWeekSkipReason.BLOCKED));
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-10: クエリ本数が週数に比例しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-10: 12週でも枠解決は範囲検索1回（週数に比例したクエリを出さない）")
    void 枠解決は範囲検索一回() {
        List<ReservationSlotEntity> twelveWeeks = new ArrayList<>();
        for (int k = 1; k <= 11; k++) {
            twelveWeeks.add(slot(9100L + k, BASE_DATE.plusWeeks(k), SlotStatus.AVAILABLE, 1, 0));
        }
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(twelveWeeks);

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 12);

        assertThat(result).as("2〜12週目の11件が解決される").hasSize(11);
        verify(slotRepository, times(1))
                .findRecurringCandidateSlots(any(), any(), any(), any(), any());
        verify(blockedTimeRepository, times(1))
                .findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(anyLong(), any(), any());
        verify(recurringBlockedTimeRepository, times(1)).findByTeamIdAndIsActiveTrue(anyLong());
        verify(reservationRepository, times(1))
                .findSlotIdsAlreadyReservedByUser(anyLong(), any(), any());
        // 「週ごとに 1 日ずつ引く」実装に退行していないことを、単発 finder の未使用で固定する。
        verify(reservationRepository, times(0))
                .existsByReservationSlotIdAndUserIdAndStatusIn(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("AC-5-10(境界): 候補枠が1件も無ければ予約照会クエリは発行しない（空 IN 句を投げない）")
    void 候補ゼロなら予約照会を発行しない() {
        given(slotRepository.findRecurringCandidateSlots(any(), any(), any(), any(), any()))
                .willReturn(List.of());

        List<ReservationRecurringSlotResolver.WeekCandidate> result =
                resolver.resolve(TEAM_ID, USER_ID, baseSlot(), 5);

        assertThat(result).hasSize(4);
        assertThat(result).allSatisfy(c ->
                assertThat(c.skipReason()).isEqualTo(RecurringWeekSkipReason.NOT_GENERATED));
        verify(reservationRepository, times(0))
                .findSlotIdsAlreadyReservedByUser(anyLong(), any(), any());
    }

    private RecurringWeekSkipReason reasonOf(
            List<ReservationRecurringSlotResolver.WeekCandidate> result, LocalDate date) {
        return result.stream()
                .filter(c -> c.date().equals(date))
                .findFirst()
                .map(ReservationRecurringSlotResolver.WeekCandidate::skipReason)
                .orElseThrow(() -> new AssertionError("該当日の解決結果がありません: " + date));
    }
}
