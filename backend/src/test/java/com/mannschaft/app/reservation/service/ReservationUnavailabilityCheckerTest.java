package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

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

    private ReservationBlockedTimeEntity block(ReservationBlockedResourceType type, Long resourceId,
                                               LocalDate date, LocalTime start, LocalTime end) {
        return ReservationBlockedTimeEntity.builder()
                .teamId(1L).blockedDate(date).startTime(start).endTime(end)
                .resourceType(type).resourceId(resourceId).build();
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
        @DisplayName("LINE/RESOURCE は MVP 未 enforce（常に非該当）")
        void 未enforce軸() {
            var b = block(ReservationBlockedResourceType.LINE, 50L, DATE, null, null);
            assertThat(checker.isBlocked(slot(50L, LocalTime.of(10, 0), LocalTime.of(11, 0)), b)).isFalse();
        }
    }

    @Nested
    @DisplayName("時間帯 overlap（半開区間）")
    class TimeOverlap {

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
}
