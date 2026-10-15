package com.mannschaft.app.repairplan.dto;

/**
 * 修繕計画項目 CSV 1 行分の生データ（バリデーション前）。
 *
 * <p>パース時は文字列のまま保持し、{@link com.mannschaft.app.repairplan.service.RepairPlanItemCsvService}
 * の preview / confirm 内で型変換する。Valkey へは CSV 原文を保存するため、本 DTO は
 * プレビュー応答用とテスト用の中間構造体として利用する。</p>
 *
 * <p>項目順は国交省「マンション修繕積立金ガイドライン」サンプル Excel に合わせる。</p>
 */
public record RepairPlanItemCsvRow(
        int rowNumber,
        String category,
        String title,
        String description,
        String plannedYear,
        String plannedMonth,
        String estimatedAmount,
        String cpiInflationBasisYear,
        String status,
        String tags
) {
}
