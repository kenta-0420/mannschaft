package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 3段締めの段2 Post への入力（CMP-260909-2152 確定設計 r12 §3・§10）。仕訳の組み立ては budget 側で行う。
 *
 * @param closeEventId   締めイベント ID（投稿判定表の一意キー）
 * @param allocationId   割当 ID
 * @param fiscalYearId   割当の年度（正本）
 * @param categoryId     費目
 * @param scopeType      TEAM／ORGANIZATION
 * @param scopeId        スコープ ID
 * @param amount         印を付けた PLANNED の合計
 * @param journalDate    仕訳日（min(締め日, 割当終了日)）
 * @param periodStart    締め期間の開始日（題名の期間表記に使う）
 * @param periodEnd      締め期間の終了日
 * @param recordedBy     記録者
 */
public record ShiftBudgetMonthlyPostCommand(UUID closeEventId, Long allocationId, Long fiscalYearId, Long categoryId,
                                            String scopeType, Long scopeId, BigDecimal amount, LocalDate journalDate,
                                            LocalDate periodStart, LocalDate periodEnd, Long recordedBy) {
}
