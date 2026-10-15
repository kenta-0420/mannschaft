package com.mannschaft.app.shiftbudget;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F08.7 シフト予算機能のフィーチャーフラグ設定プロパティ。
 *
 * <p>{@code feature.shift-budget.enabled} で機能全体を有効/無効化する。</p>
 *
 * <ul>
 *   <li>{@code application.yml} で {@code false} (default)</li>
 *   <li>{@code application-test.yml} で {@code true}</li>
 *   <li>{@code application-prod.yml} で {@code false} 維持</li>
 * </ul>
 *
 * <p>Phase 9-δ で組織別フラグ ({@code budget_configs.shift_budget_enabled}) を導入し
 * 三値論理 (グローバル × 組織) に拡張済み（{@link ShiftBudgetFeatureService} 参照）。</p>
 *
 * <p>{@code feature.shift-budget.monthly-close-cron-enabled} は月次締めバッチ
 * ({@code MonthlyShiftBudgetCloseBatchJob}) の自動起動有無を切替する。
 * マスター御裁可 Q4 により、デフォルトは false（テスト/本番では明示的に true にしないと動かない）。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "feature.shift-budget")
public class ShiftBudgetProperties {

    /** シフト予算機能の有効/無効フラグ（デフォルト false）。 */
    private boolean enabled = false;

    /**
     * 月次締めバッチの cron 自動起動の有効/無効フラグ（Phase 9-δ 追加、マスター御裁可 Q4）。
     *
     * <p>true でも {@link #enabled} が false なら個別組織判定でスキップされるため二重防御。
     * デフォルト false（test/prod ともに OFF。手動 API 起動 #11 でのみ動かす運用が安全）。</p>
     */
    private boolean monthlyCloseCronEnabled = false;
}
