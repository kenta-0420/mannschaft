package com.mannschaft.app.shift.dto;

import com.mannschaft.app.shift.ShiftAssignmentStatus;

import java.math.BigDecimal;

/**
 * 提案された割当情報DTO。
 *
 * @param id     割当ID
 * @param slotId スロットID
 * @param userId ユーザーID
 * @param status 割当ステータス
 * @param score  割当スコア
 * @param note   備考
 */
public record ProposedAssignmentDto(
        Long id,
        Long slotId,
        Long userId,
        ShiftAssignmentStatus status,
        BigDecimal score,
        String note
) {}
