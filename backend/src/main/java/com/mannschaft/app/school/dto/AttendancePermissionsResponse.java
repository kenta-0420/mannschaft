package com.mannschaft.app.school.dto;

/**
 * 学校出欠の権限判定結果レスポンス（AC-23）。
 *
 * <p>FE は独自に判定せず、この結果だけで入口・ボタンを出し分ける。</p>
 *
 * @param teamId          クラスチームID
 * @param canView         閲覧可（V）
 * @param canRecordDaily  日次出欠の登録可（R）
 * @param canRecordPeriod 時限出欠の登録・修正可（P。第1段は R と同じ）
 */
public record AttendancePermissionsResponse(
        Long teamId,
        boolean canView,
        boolean canRecordDaily,
        boolean canRecordPeriod) {
}
