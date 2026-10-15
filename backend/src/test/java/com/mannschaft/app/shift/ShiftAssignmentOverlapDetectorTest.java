package com.mannschaft.app.shift;

import com.mannschaft.app.shift.service.ShiftAssignmentOverlapDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShiftAssignmentOverlapDetector} の重なり判定テスト（試練 / red 先行）。
 *
 * <p>設計 F03.5 §11.3.5 の AC-3-01〜06 を固定する。
 * 金型は同ドメインの {@code ShiftSlotTimeValidationServiceTest}（戦役 A-1）。</p>
 *
 * <p><b>半開区間 {@code [start, end)}</b> であること、および日跨ぎ枠を
 * {@code LocalDateTime} 区間へ展開して比較することが判定の中心である。</p>
 */
@DisplayName("シフト割当の重なり判定（AC-3群 UT）")
class ShiftAssignmentOverlapDetectorTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 3, 2);
    private static final LocalDate DAY2 = LocalDate.of(2026, 3, 3);

    @Test
    @DisplayName("AC-3-01 完全一致（09:00-12:00 と 09:00-12:00）は重なる")
    void AC_3_01_完全一致は重なる() {
        assertThat(ShiftAssignmentOverlapDetector.overlaps(
                DAY1, LocalTime.of(9, 0), LocalTime.of(12, 0), false,
                DAY1, LocalTime.of(9, 0), LocalTime.of(12, 0), false))
                .isTrue();
    }

    @Test
    @DisplayName("AC-3-02 部分重なり（09:00-12:00 と 11:00-14:00）は重なる")
    void AC_3_02_部分重なりは重なる() {
        assertThat(ShiftAssignmentOverlapDetector.overlaps(
                DAY1, LocalTime.of(9, 0), LocalTime.of(12, 0), false,
                DAY1, LocalTime.of(11, 0), LocalTime.of(14, 0), false))
                .isTrue();
    }

    @Test
    @DisplayName("AC-3-03 隣接（09:00-12:00 と 12:00-15:00）は重ならない（半開区間）")
    void AC_3_03_隣接は重ならない() {
        assertThat(ShiftAssignmentOverlapDetector.overlaps(
                DAY1, LocalTime.of(9, 0), LocalTime.of(12, 0), false,
                DAY1, LocalTime.of(12, 0), LocalTime.of(15, 0), false))
                .isFalse();
    }

    @Test
    @DisplayName("AC-3-04 包含（09:00-18:00 と 12:00-13:00）は重なる")
    void AC_3_04_包含は重なる() {
        assertThat(ShiftAssignmentOverlapDetector.overlaps(
                DAY1, LocalTime.of(9, 0), LocalTime.of(18, 0), false,
                DAY1, LocalTime.of(12, 0), LocalTime.of(13, 0), false))
                .isTrue();
    }

    @Test
    @DisplayName("AC-3-05 別日の同一時刻は重ならない")
    void AC_3_05_別日の同一時刻は重ならない() {
        assertThat(ShiftAssignmentOverlapDetector.overlaps(
                DAY1, LocalTime.of(9, 0), LocalTime.of(12, 0), false,
                DAY2, LocalTime.of(9, 0), LocalTime.of(12, 0), false))
                .isFalse();
    }

    @Test
    @DisplayName("AC-3-06 日跨ぎ枠（22:00-02:00 + endsNextDay）と翌日 01:00-05:00 は重なる")
    void AC_3_06_日跨ぎ枠と翌日枠は重なる() {
        assertThat(ShiftAssignmentOverlapDetector.overlaps(
                DAY1, LocalTime.of(22, 0), LocalTime.of(2, 0), true,
                DAY2, LocalTime.of(1, 0), LocalTime.of(5, 0), false))
                .isTrue();
    }
}
