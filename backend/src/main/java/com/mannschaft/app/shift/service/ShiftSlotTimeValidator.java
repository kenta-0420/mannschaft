package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;

import java.time.Duration;
import java.time.LocalTime;

/**
 * シフト枠の時刻検証ユーティリティ（単一の検証点）。
 *
 * <p>予約側 {@code com.mannschaft.app.reservation.service.SlotTimeValidator} と同型。
 * 刻みのみ 15 分で非対称（設計 F03.5 §11.2.5・マスター裁可済み）。
 * <b>将来 {@code common} へ統合する候補</b>。</p>
 *
 * <p>規則（設計 F03.5 §11.2.5）:</p>
 * <ol>
 *   <li>{@code start < end}（{@code endsNextDay = false} のとき）</li>
 *   <li>分は 15 の倍数、秒・ナノ秒は 0</li>
 *   <li>最小 15 分</li>
 *   <li>24 時間未満（24 時間ちょうどは不可）</li>
 *   <li>日跨ぎは {@code endsNextDay} で明示する（暗黙の {@code end < start} を許さない）</li>
 * </ol>
 *
 * <p><b>既存データ互換</b>: 書き込み時のみ検証する。時刻を触らない更新は、既存の不正時刻を
 * 理由に拒否してはならない（呼び出し側で「合成後の時刻が変化する場合のみ」呼ぶ）。</p>
 */
public final class ShiftSlotTimeValidator {

    /** シフト枠の最小グリッド（分）。start/end の分はこの倍数でなければならない。 */
    public static final int SLOT_GRANULARITY_MINUTES = 15;

    private ShiftSlotTimeValidator() {
        // ユーティリティクラス
    }

    /**
     * 時間帯を検証する（日跨ぎなし）。
     *
     * @param startTime 開始時刻
     * @param endTime   終了時刻
     */
    public static void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        validateTimeRange(startTime, endTime, false);
    }

    /**
     * 日跨ぎを明示した時間帯を検証する。
     *
     * <p>日跨ぎ {@code true} は必ず {@code end < start}、{@code false} は必ず {@code start < end}
     * とし、同一時刻（0 分枠・24 時間枠）はいずれも許可しない。</p>
     *
     * @param startTime   開始時刻
     * @param endTime     終了時刻
     * @param endsNextDay 翌日終了なら true
     * @throws BusinessException 前後関係が不正なら {@link ShiftErrorCode#INVALID_TIME_RANGE}、
     *                           刻み・枠長が不正なら {@link ShiftErrorCode#INVALID_SLOT_GRANULARITY}
     */
    public static void validateTimeRange(LocalTime startTime, LocalTime endTime, boolean endsNextDay) {
        if (startTime == null || endTime == null) {
            if (endsNextDay) {
                // 日跨ぎを主張しながら時刻が揃っていないのは矛盾（片側据え置きでは表現できない）。
                throw new BusinessException(ShiftErrorCode.INVALID_TIME_RANGE);
            }
            return;
        }
        boolean validOrder = endsNextDay ? endTime.isBefore(startTime) : startTime.isBefore(endTime);
        if (!validOrder) {
            throw new BusinessException(ShiftErrorCode.INVALID_TIME_RANGE);
        }
        long minutes = durationMinutes(startTime, endTime, endsNextDay);
        if (!isOnGranularityGrid(startTime) || !isOnGranularityGrid(endTime)
                || minutes < SLOT_GRANULARITY_MINUTES
                || minutes >= Duration.ofDays(1).toMinutes()) {
            throw new BusinessException(ShiftErrorCode.INVALID_SLOT_GRANULARITY);
        }
    }

    /**
     * 枠長を分で返す。
     *
     * @param startTime   開始時刻
     * @param endTime     終了時刻
     * @param endsNextDay 翌日終了なら true
     * @return 枠長（分）
     */
    public static long durationMinutes(LocalTime startTime, LocalTime endTime, boolean endsNextDay) {
        long minutes = Duration.between(startTime, endTime).toMinutes();
        return endsNextDay ? minutes + Duration.ofDays(1).toMinutes() : minutes;
    }

    /**
     * 時刻が 15 分グリッド（分が 15 の倍数、秒・ナノ秒が 0）に乗っているか判定する。
     */
    private static boolean isOnGranularityGrid(LocalTime time) {
        return time.getMinute() % SLOT_GRANULARITY_MINUTES == 0
                && time.getSecond() == 0
                && time.getNano() == 0;
    }
}
