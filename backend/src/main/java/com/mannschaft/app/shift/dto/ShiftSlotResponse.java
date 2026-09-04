package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * シフト枠レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ShiftSlotResponse {

    Long id;
    Long scheduleId;

    ShiftSlotTimeDto     time;      // slotDate, startTime, endTime
    ShiftSlotPositionDto position;  // positionId, positionName, requiredCount
    List<Long>           assignedUserIds;
    String               note;

    /**
     * 割当内容（{@code assignedUserIds}）を閲覧者に伏せたか（CMP-260826-2127 / AC-4）。
     *
     * <p>非管理者が {@code COLLECTING} / {@code ADJUSTING} のシフト表の枠を取得したときだけ
     * {@code true} になり、そのとき {@code assignedUserIds} は必ず空配列（{@code null} ではない）。
     * 既定は {@code false}（fail-open にしないための既定値の向き）。</p>
     *
     * <p>FE は「本当に誰も割り当たっていない枠」と「伏せた枠」をこのフラグで区別する。
     * これが無いと一般メンバーの画面で全枠が赤の「0/N」と誤表示される。</p>
     */
    boolean              assignmentMasked;

    public record ShiftSlotTimeDto(LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}
    public record ShiftSlotPositionDto(Long positionId, String positionName, Integer requiredCount) {}
}
