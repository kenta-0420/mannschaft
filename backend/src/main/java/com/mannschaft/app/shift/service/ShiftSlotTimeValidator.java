package com.mannschaft.app.shift.service;

import java.time.Duration;
import java.time.LocalTime;

/**
 * シフト枠の時刻検証ユーティリティ（単一の検証点）。
 *
 * <p>予約側 {@code com.mannschaft.app.reservation.service.SlotTimeValidator} と同型。
 * 刻みのみ 15 分で非対称（設計 F03.5 §11.2.5・マスター裁可済み）。
 * <b>将来 {@code common} へ統合する候補</b>。</p>
 *
 * <p><b>【試練スタブ】</b> 本クラスは試練（テスト先行）でテストをコンパイル可能にするための
 * <b>検証を一切行わない空実装</b>である。出陣（実装）で §11.2.5 の規則 1〜5 を実装した
 * 本実装へ置き換えること。</p>
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
     * @param startTime   開始時刻
     * @param endTime     終了時刻
     * @param endsNextDay 翌日終了なら true
     */
    public static void validateTimeRange(LocalTime startTime, LocalTime endTime, boolean endsNextDay) {
        // 【試練スタブ】検証未実装。
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
}
