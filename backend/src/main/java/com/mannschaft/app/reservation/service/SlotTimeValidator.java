package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 予約枠・週間テンプレート共通の時刻検証ユーティリティ（単一の検証点）。
 *
 * <p>F03.4.2 §3.2「{@code start_time}/{@code end_time} は 30 分単位・{@code start < end}・最小 30 分
 * （既存の枠バリデーション 007/022 を<b>再利用</b>。テンプレ専用コードは設けない）」を実現するため、
 * {@code ReservationSlotService} の従来 private 検証をここへ抽出し、
 * {@code ReservationSlotTemplateService} と共有する（別実装厳禁）。</p>
 */
final class SlotTimeValidator {

    /** 予約枠の最小グリッド（分）。start/end の分はこの倍数（00 / 30）でなければならない。 */
    static final int SLOT_GRANULARITY_MINUTES = 30;

    private SlotTimeValidator() {
        // ユーティリティクラス
    }

    /**
     * 時間範囲のバリデーション。
     *
     * <ol>
     *   <li>start &lt; end（{@link ReservationErrorCode#INVALID_TIME_RANGE}=007・400）</li>
     *   <li>30 分グリッド: start/end の分が {@code 00} または {@code 30} のみ、かつ枠長 &ge; 30 分
     *       （{@link ReservationErrorCode#INVALID_SLOT_GRANULARITY}=022・400）</li>
     * </ol>
     * 片方のみ指定（部分更新で時刻据え置き等）の場合は検証をスキップする
     * （呼び出し側で「両方非 null のときのみ」呼ぶ前提）。
     */
    static void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        validateTimeRange(startTime, endTime, false);
    }

    /**
     * 日跨ぎを明示した時間帯を検証する。日跨ぎ true は必ず end&lt;start、false は end&gt;start
     * とし、同一時刻（24時間枠）を許可しない。区間は最大24時間未満である。
     */
    static void validateTimeRange(LocalTime startTime, LocalTime endTime, boolean endsNextDay) {
        if (startTime == null || endTime == null) {
            return;
        }
        boolean validOrder = endsNextDay ? endTime.isBefore(startTime) : startTime.isBefore(endTime);
        if (!validOrder) {
            throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
        }
        if (!isOnGranularityGrid(startTime) || !isOnGranularityGrid(endTime)
                || durationMinutes(startTime, endTime, endsNextDay) < SLOT_GRANULARITY_MINUTES
                || durationMinutes(startTime, endTime, endsNextDay) >= Duration.ofDays(1).toMinutes()) {
            throw new BusinessException(ReservationErrorCode.INVALID_SLOT_GRANULARITY);
        }
    }

    static long durationMinutes(LocalTime startTime, LocalTime endTime, boolean endsNextDay) {
        long minutes = Duration.between(startTime, endTime).toMinutes();
        return endsNextDay ? minutes + Duration.ofDays(1).toMinutes() : minutes;
    }

    /**
     * 時刻が 30 分グリッド（分が 00 / 30、秒・ナノ秒が 0）に乗っているか判定する。
     */
    private static boolean isOnGranularityGrid(LocalTime time) {
        return time.getMinute() % SLOT_GRANULARITY_MINUTES == 0
                && time.getSecond() == 0
                && time.getNano() == 0;
    }
}
