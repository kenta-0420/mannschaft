package com.mannschaft.app.repairplan.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * シミュレーション入力パラメータ（不変）。
 * repair_plan_items の年度別集計は yearlyPlannedExpenses に格納して渡す。
 */
public record SimulationParams(
        BigDecimal initialBalance,           // v_repair_fund_balance から取得
        BigDecimal monthlyFee,               // 1戸あたり月額積立金
        int dwellingUnits,                   // 戸数
        BigDecimal reserveInflationRate,     // 月額値上げ年率（例: 0.015）
        BigDecimal cpiInflationRate,         // 物価上昇率（例: 0.015）
        int deferralYears,                   // 修繕延期年数
        BigDecimal loanPrincipal,            // 借入金元本
        BigDecimal loanInterestRate,         // 借入金利率
        int loanTermYears,                   // 借入返済期間（年）
        BigDecimal fixedManagementCostYearly,// 年間固定管理費
        int scenarioHorizonYears,            // シミュレーション期間（1〜50）
        LocalDateTime baselineAt,            // 計算基準日時
        Map<Integer, BigDecimal> yearlyPlannedExpenses // year → 修繕予定費SUM（repair_plan_itemsから）
) {}
