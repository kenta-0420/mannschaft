package com.mannschaft.app.shift.assignment;

import com.mannschaft.app.shift.dto.AssignmentParametersDto;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;

import java.util.List;

/**
 * 自動割当アルゴリズムへの入力コンテキスト。
 *
 * @param scheduleId  スケジュールID
 * @param slots       割当対象のシフト枠リスト
 * @param requests    スケジュール全体のシフト希望リスト
 * @param constraints チームおよびメンバー個別の勤務制約リスト
 * @param parameters  割当パラメータ
 */
public record AssignmentContext(
        Long scheduleId,
        List<ShiftSlotEntity> slots,
        List<ShiftRequestEntity> requests,
        List<MemberWorkConstraintEntity> constraints,
        AssignmentParametersDto parameters
) {}
