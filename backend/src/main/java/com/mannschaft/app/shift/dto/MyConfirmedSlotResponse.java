package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * ログインユーザーの確定シフト枠レスポンスDTO。
 *
 * <p>GET /api/v1/shifts/my/confirmed-slots で返却する。
 * ShiftAssignmentStatus.CONFIRMED の割当のみを対象とする。</p>
 */
@Builder
@Getter
public class MyConfirmedSlotResponse {

    /** シフト枠ID */
    private Long slotId;

    /** シフト日付 */
    private LocalDate slotDate;

    /** 開始時刻 */
    private LocalTime startTime;

    /** 終了時刻 */
    private LocalTime endTime;

    /** チームID */
    private Long teamId;

    /** チーム名 */
    private String teamName;

    /** シフトスケジュールID */
    private Long scheduleId;

    /** シフトスケジュール名（title） */
    private String scheduleName;

    /** ポジション名（ポジション未設定の場合は null） */
    private String positionName;
}
