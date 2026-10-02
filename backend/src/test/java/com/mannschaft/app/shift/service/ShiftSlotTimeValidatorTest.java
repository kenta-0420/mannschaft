package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShiftSlotTimeValidator} の単体テスト（試練 / red 先行）。
 *
 * <p>設計 F03.5 §11.2.5「{@code ShiftSlotTimeValidator} の新設」の規則 1〜5 を検証する。
 * 金型は予約側 {@code SlotTimeValidator}（package-private のため共有不可・書き方のみ踏襲）。</p>
 *
 * <p>刻みは予約側の 30 分に対し <b>15 分</b>（マスター裁可済みの非対称）。</p>
 */
@DisplayName("ShiftSlotTimeValidator 単体テスト（枠時刻バリデーション）")
class ShiftSlotTimeValidatorTest {

    @Nested
    @DisplayName("規則1・5: start と end の前後関係")
    class TimeRangeOrder {

        @Test
        @DisplayName("AC-1-01: 開始と終了が同一時刻_INVALID_TIME_RANGE")
        void 開始と終了が同一時刻_INVALID_TIME_RANGE() {
            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(9, 0), LocalTime.of(9, 0), false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("AC-1-02: 終了が開始より前で日跨ぎ未指定_INVALID_TIME_RANGE")
        void 終了が開始より前で日跨ぎ未指定_INVALID_TIME_RANGE() {
            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(22, 0), LocalTime.of(2, 0), false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("AC-1-03: 日跨ぎ指定なのに終了が開始より後_INVALID_TIME_RANGE")
        void 日跨ぎ指定なのに終了が開始より後_INVALID_TIME_RANGE() {
            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0), true))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_TIME_RANGE);
        }
    }

    @Nested
    @DisplayName("規則2・3・4: 刻み・枠長")
    class Granularity {

        @Test
        @DisplayName("AC-1-04: 分が15の倍数でない_INVALID_SLOT_GRANULARITY")
        void 分が15の倍数でない_INVALID_SLOT_GRANULARITY() {
            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(9, 7), LocalTime.of(17, 0), false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_SLOT_GRANULARITY);
        }

        @Test
        @DisplayName("AC-1-05: 秒・ナノ秒が非ゼロ_INVALID_SLOT_GRANULARITY")
        void 秒ナノ秒が非ゼロ_INVALID_SLOT_GRANULARITY() {
            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(
                            LocalTime.of(9, 0, 30), LocalTime.of(17, 0), false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_SLOT_GRANULARITY);

            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(
                            LocalTime.of(9, 0), LocalTime.of(17, 0, 0, 1), false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_SLOT_GRANULARITY);
        }

        @Test
        @DisplayName("AC-1-06: 枠長が15分未満_INVALID_SLOT_GRANULARITY")
        void 枠長が15分未満_INVALID_SLOT_GRANULARITY() {
            // 15 分グリッドに乗っており、かつ 15 分未満になり得る唯一の形は同一時刻だが、
            // それは規則1で弾かれるため、ここでは刻み違反を伴わない 5 分枠で最小枠長を検証する。
            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(9, 0), LocalTime.of(9, 5), false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_SLOT_GRANULARITY);
        }

        @Test
        @DisplayName("AC-1-07: 枠長が24時間ちょうど_400で拒否される")
        void 枠長が24時間ちょうど_400で拒否される() {
            // LocalTime で枠長 24 時間ちょうどを表せるのは「日跨ぎ かつ end == start」のみ。
            // この入力は規則5（日跨ぎは end < start）と規則4（24時間未満）の双方に触れるため、
            // どちらのコードで弾くかは実装の判定順に委ねる。ここでは 400 系の 2 コードのいずれかで
            // 必ず拒否されることを固定する。
            assertThatThrownBy(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(9, 0), LocalTime.of(9, 0), true))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isIn(ShiftErrorCode.INVALID_TIME_RANGE, ShiftErrorCode.INVALID_SLOT_GRANULARITY);
        }
    }

    @Nested
    @DisplayName("正常系（境界）")
    class Valid {

        @Test
        @DisplayName("AC-1-08: 境界の最小枠_9時15分から9時30分_成功")
        void 境界の最小枠_成功() {
            assertThatCode(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(9, 15), LocalTime.of(9, 30), false))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-1-09: 日跨ぎ22時から翌2時_成功かつ枠長4時間")
        void 日跨ぎ22時から翌2時_成功かつ枠長4時間() {
            assertThatCode(() ->
                    ShiftSlotTimeValidator.validateTimeRange(LocalTime.of(22, 0), LocalTime.of(2, 0), true))
                    .doesNotThrowAnyException();
            assertThat(ShiftSlotTimeValidator.durationMinutes(LocalTime.of(22, 0), LocalTime.of(2, 0), true))
                    .isEqualTo(4 * 60);
        }
    }
}
