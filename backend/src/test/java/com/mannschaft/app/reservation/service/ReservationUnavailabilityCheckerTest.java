package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservationUnavailabilityChecker}（機能B・§5.B の単一 overlap ユーティリティ）の単体テスト。
 *
 * <p>受け入れ条件 B-1〜B-4・B-10（3 箇所整合の source-of-truth）の overlap 判定ロジックを、
 * 全日/部分/半開境界/対象軸（TEAM/STAFF）/共通枠（staff=null）の観点で番人化する。</p>
 */
@DisplayName("ReservationUnavailabilityChecker 単体テスト（§5.B overlap）")
class ReservationUnavailabilityCheckerTest {

    private final ReservationUnavailabilityChecker checker = new ReservationUnavailabilityChecker();

    private static final LocalDate DATE = LocalDate.of(2026, 4, 1);

    private ReservationSlotEntity slot(Long staffUserId, LocalTime start, LocalTime end) {
        return ReservationSlotEntity.builder()
                .teamId(1L).staffUserId(staffUserId).slotDate(DATE).startTime(start).endTime(end).build();
    }

    private ReservationSlotEntity slotWithLine(Long lineId, LocalTime start, LocalTime end) {
        return ReservationSlotEntity.builder()
                .teamId(1L).lineId(lineId).slotDate(DATE).startTime(start).endTime(end).build();
    }

    private ReservationBlockedTimeEntity block(ReservationBlockedResourceType type, Long resourceId,
                                               LocalDate date, LocalTime start, LocalTime end) {
        return ReservationBlockedTimeEntity.builder()
                .teamId(1L).blockedDate(date).startTime(start).endTime(end)
                .resourceType(type).resourceId(resourceId).build();
    }

    private ReservationRecurringBlockedTimeEntity rule(Long lineId, ReservationDayOfWeek dayOfWeek,
                                                        LocalTime start, LocalTime end, boolean active) {
        return ReservationRecurringBlockedTimeEntity.builder()
                .teamId(1L).lineId(lineId).dayOfWeek(dayOfWeek).startTime(start).endTime(end)
                .reason("研修").isPublic(true).isActive(active).build();
    }

    @Nested
    @DisplayName("日付・対象軸マッチ")
    class DateAndResource {

        @Test
        @DisplayName("別日はブロックしない")
        void 別日は非該当() {
            assertThat(checker.isBlocked(
                    slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                    block(ReservationBlockedResourceType.TEAM, null, DATE.plusDays(1), null, null))).isFalse();
        }

        @Test
        @DisplayName("B-2: STAFF 軸は resource_id と一致する slot のみ該当")
        void STAFF軸一致() {
            var b = block(ReservationBlockedResourceType.STAFF, 50L, DATE, null, null);
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isTrue();
            assertThat(checker.isBlocked(slot(60L, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isFalse();
        }

        @Test
        @DisplayName("B-2: STAFF 軸は共通枠（staff=null）を該当させない")
        void STAFF軸は共通枠を除外しない() {
            var b = block(ReservationBlockedResourceType.STAFF, 50L, DATE, null, null);
            assertThat(checker.isBlocked(slot(null, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isFalse();
        }

        @Test
        @DisplayName("F03.4.5 §4.2 併載: LINE軸は line_id と一致する slot のみ該当（共通枠は非該当）")
        void LINE軸一致() {
            var b = block(ReservationBlockedResourceType.LINE, 70L, DATE, null, null);
            assertThat(checker.isBlocked(slotWithLine(70L, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isTrue();
            assertThat(checker.isBlocked(slotWithLine(80L, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isFalse();
            assertThat(checker.isBlocked(slotWithLine(null, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isFalse();
        }

        @Test
        @DisplayName("RESOURCE は引き続き MVP 未 enforce（常に非該当）")
        void RESOURCE軸は未enforce() {
            var b = block(ReservationBlockedResourceType.RESOURCE, 70L, DATE, null, null);
            assertThat(checker.isBlocked(slotWithLine(70L, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isFalse();
        }
    }

    @Nested
    @DisplayName("時間帯 overlap（半開区間）")
    class TimeOverlap {

        @Test
        @DisplayName("日跨ぎ単発 blocked は翌日セルへ反映し、半開区間の隣接は重複しない")
        void overnightBlockedUsesNextDayHalfOpenInterval() {
            assertThat(checker.overlaps(
                    DATE.plusDays(1), DATE.plusDays(1), LocalTime.of(0, 30), LocalTime.of(1, 30),
                    DATE, DATE.plusDays(1), LocalTime.of(23, 0), LocalTime.of(1, 0),
                    ZoneId.of("Asia/Tokyo"))).isTrue();
            assertThat(checker.overlaps(
                    DATE.plusDays(1), DATE.plusDays(1), LocalTime.of(1, 0), LocalTime.of(2, 0),
                    DATE, DATE.plusDays(1), LocalTime.of(22, 0), LocalTime.of(1, 0),
                    ZoneId.of("Asia/Tokyo"))).isFalse();
        }

        @Test
        @DisplayName("B-4: 全日（start/end 両 NULL）はその日・軸の全 slot に該当")
        void 全日() {
            var b = block(ReservationBlockedResourceType.TEAM, null, DATE, null, null);
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(9, 0), LocalTime.of(9, 30)), b)).isTrue();
            assertThat(checker.isBlocked(slot(null, LocalTime.of(23, 0), LocalTime.of(23, 30)), b)).isTrue();
        }

        @Test
        @DisplayName("B-3: 部分ブロック[10:00,11:00]は重なる slot を該当とする")
        void 部分重複() {
            var b = block(ReservationBlockedResourceType.TEAM, null, DATE, LocalTime.of(10, 0), LocalTime.of(11, 0));
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isTrue();
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(10, 30), LocalTime.of(11, 30)), b)).isTrue();
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(9, 30), LocalTime.of(10, 30)), b)).isTrue();
        }

        @Test
        @DisplayName("B-3: 半開区間 — slot.start == blocked.end（隣接）は非該当")
        void 半開境界_隣接後は非該当() {
            var b = block(ReservationBlockedResourceType.TEAM, null, DATE, LocalTime.of(10, 0), LocalTime.of(11, 0));
            // 隣接（後）: [11:00,12:00] は非該当
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(11, 0), LocalTime.of(12, 0)), b)).isFalse();
            // 隣接（前）: [09:00,10:00] は非該当（blocked.start==slot.end）
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(9, 0), LocalTime.of(10, 0)), b)).isFalse();
        }
    }

    @Nested
    @DisplayName("定期予約不可枠 isRecurringBlocked（F03.4.5 §4.2 W2-2）")
    class Recurring {

        @Test
        @DisplayName("前日開始の日跨ぎ繰返し blocked は翌日の深夜セルへ反映する")
        void overnightRecurringRuleCarriesIntoNextDay() {
            LocalDate nextDay = DATE.plusDays(1);
            ReservationDayOfWeek previousDay = ReservationDayOfWeek.from(DATE);
            assertThat(checker.isRecurringBlocked(
                    nextDay, nextDay, LocalTime.of(0, 30), LocalTime.of(1, 0), null,
                    true, previousDay, LocalTime.of(23, 0), LocalTime.of(1, 0), null, true,
                    ZoneId.of("Asia/Tokyo"))).isTrue();
        }

        @Test
        @DisplayName("曜日一致・時間overlapで該当")
        void 曜日時間一致() {
            var r = rule(null, ReservationDayOfWeek.from(DATE), LocalTime.of(9, 0), LocalTime.of(10, 0), true);
            assertThat(checker.isRecurringBlocked(slot(null, LocalTime.of(9, 30), LocalTime.of(10, 30)), r)).isTrue();
        }

        @Test
        @DisplayName("曜日不一致は非該当")
        void 曜日不一致() {
            ReservationDayOfWeek today = ReservationDayOfWeek.from(DATE);
            ReservationDayOfWeek other = today == ReservationDayOfWeek.MON
                    ? ReservationDayOfWeek.TUE : ReservationDayOfWeek.MON;
            var r = rule(null, other, LocalTime.of(9, 0), LocalTime.of(10, 0), true);
            assertThat(checker.isRecurringBlocked(slot(null, LocalTime.of(9, 30), LocalTime.of(10, 30)), r)).isFalse();
        }

        @Test
        @DisplayName("is_active=false（一時停止）は非該当")
        void 無効ルールは非該当() {
            var r = rule(null, ReservationDayOfWeek.from(DATE), LocalTime.of(9, 0), LocalTime.of(10, 0), false);
            assertThat(checker.isRecurringBlocked(slot(null, LocalTime.of(9, 30), LocalTime.of(10, 30)), r)).isFalse();
        }

        @Test
        @DisplayName("lineId=null はチーム全体（共通枠含む全slot）に該当")
        void ライン全体() {
            var r = rule(null, ReservationDayOfWeek.from(DATE), LocalTime.of(9, 0), LocalTime.of(10, 0), true);
            assertThat(checker.isRecurringBlocked(
                    slotWithLine(null, LocalTime.of(9, 30), LocalTime.of(10, 0)), r)).isTrue();
            assertThat(checker.isRecurringBlocked(
                    slotWithLine(99L, LocalTime.of(9, 30), LocalTime.of(10, 0)), r)).isTrue();
        }

        @Test
        @DisplayName("lineId指定時は一致するラインのslotのみ・共通枠は対象外")
        void ライン指定は一致のみ() {
            var r = rule(70L, ReservationDayOfWeek.from(DATE), LocalTime.of(9, 0), LocalTime.of(10, 0), true);
            assertThat(checker.isRecurringBlocked(
                    slotWithLine(70L, LocalTime.of(9, 30), LocalTime.of(10, 0)), r)).isTrue();
            assertThat(checker.isRecurringBlocked(
                    slotWithLine(80L, LocalTime.of(9, 30), LocalTime.of(10, 0)), r)).isFalse();
            assertThat(checker.isRecurringBlocked(
                    slotWithLine(null, LocalTime.of(9, 30), LocalTime.of(10, 0)), r)).isFalse();
        }

        @Test
        @DisplayName("半開区間: slot.start == rule.end（隣接後）は非該当")
        void 半開境界() {
            var r = rule(null, ReservationDayOfWeek.from(DATE), LocalTime.of(9, 0), LocalTime.of(10, 0), true);
            assertThat(checker.isRecurringBlocked(
                    slot(null, LocalTime.of(10, 0), LocalTime.of(10, 30)), r)).isFalse();
        }

        @Test
        @DisplayName("境界: 18:30-19:00/20:00-20:30 は 19-20 ルールと非重複（半開区間の実務ケース）")
        void 境界ケース() {
            var r = rule(null, ReservationDayOfWeek.from(DATE), LocalTime.of(19, 0), LocalTime.of(20, 0), true);
            assertThat(checker.isRecurringBlocked(
                    slot(null, LocalTime.of(18, 30), LocalTime.of(19, 0)), r)).isFalse();
            assertThat(checker.isRecurringBlocked(
                    slot(null, LocalTime.of(20, 0), LocalTime.of(20, 30)), r)).isFalse();
        }
    }

    @Nested
    @DisplayName("isBlockedByAny 集約（単発+定期・F03.4.5 §4.2）")
    class AggregatedByAny {

        @Test
        @DisplayName("単発が空でも定期ルールで該当すればtrue")
        void 定期のみ該当() {
            ReservationSlotEntity target = slot(50L, LocalTime.of(19, 0), LocalTime.of(20, 0));
            var r = rule(null, ReservationDayOfWeek.from(DATE), LocalTime.of(19, 0), LocalTime.of(20, 0), true);
            assertThat(checker.isBlockedByAny(target, List.of(), List.of(r))).isTrue();
        }

        @Test
        @DisplayName("単発・定期いずれも非該当ならfalse")
        void いずれも非該当() {
            ReservationSlotEntity target = slot(50L, LocalTime.of(8, 0), LocalTime.of(9, 0));
            var r = rule(null, ReservationDayOfWeek.from(DATE), LocalTime.of(19, 0), LocalTime.of(20, 0), true);
            assertThat(checker.isBlockedByAny(target, List.of(), List.of(r))).isFalse();
        }

        @Test
        @DisplayName("単発（機能B）該当は引き続きtrue（既存の isBlockedByAny(2引数) と整合）")
        void 単発該当は従来通り() {
            ReservationSlotEntity target = slot(50L, LocalTime.of(19, 0), LocalTime.of(20, 0));
            var b = block(ReservationBlockedResourceType.TEAM, null, DATE, LocalTime.of(19, 0), LocalTime.of(20, 0));
            assertThat(checker.isBlockedByAny(target, List.of(b), List.of())).isTrue();
        }
    }
}
