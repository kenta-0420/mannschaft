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

    ShiftSlotTimeDto     time;      // slotDate, startTime, endTime, endsNextDay
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

    /**
     * 割当操作に伴う警告（設計 F03.5 §11.3.5）。
     *
     * <p><b>{@code PATCH /shifts/slots/{id}/assignments} のレスポンスでのみ非 null</b> となり、
     * 警告が無い場合は空配列。参照系（一覧・単体取得）では {@code null}（= 判定していない）である。
     * 「警告が無い」と「判定していない」を空配列と {@code null} で区別する。</p>
     *
     * <p><b>別レスポンス型を新設せず既存 DTO に生やした理由</b>: 警告は枠の状態と一緒に
     * 表示されるもので、FE は更新後の枠情報と警告を同時に必要とする。二重の型を作ると
     * 呼び出し側が枠情報を二度組み立てることになり、既存の {@code ShiftSlotResponse} を
     * 返す契約（FE の型・既存 IT）も壊れる。</p>
     */
    List<ShiftAssignmentWarningDto> warnings;

    /**
     * 枠の時間帯。
     *
     * <p>{@code endsNextDay} は翌日終了（日跨ぎ）を<b>明示</b>するフラグ（設計 F03.5 §11.2.5 規則5）。
     * これを返さないと、クライアントは禁じたはずの {@code endTime < startTime} という
     * 暗黙の推測に戻らざるを得ず、移行済みの既存行とも区別できない。</p>
     *
     * @param slotDate    シフト日付（日跨ぎ枠は開始日）
     * @param startTime   開始時刻
     * @param endTime     終了時刻
     * @param endsNextDay 翌日終了なら true
     */
    public record ShiftSlotTimeDto(LocalDate slotDate, LocalTime startTime, LocalTime endTime,
                                   boolean endsNextDay) {}
    public record ShiftSlotPositionDto(Long positionId, String positionName, Integer requiredCount) {}
}
