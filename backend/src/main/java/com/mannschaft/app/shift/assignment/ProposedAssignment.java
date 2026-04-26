package com.mannschaft.app.shift.assignment;

import java.math.BigDecimal;

/**
 * アルゴリズムが生成した割当提案。
 *
 * @param slotId スロットID
 * @param userId ユーザーID
 * @param score  割当スコア
 */
public record ProposedAssignment(
        Long slotId,
        Long userId,
        BigDecimal score
) {}
