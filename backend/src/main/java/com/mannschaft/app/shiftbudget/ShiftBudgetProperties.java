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
 * <p>Phase 9-α では <strong>グローバルフラグ単独判定</strong> のみ実装。
 * Phase 9-δ で組織別フラグ ({@code budget_configs.shift_budget_enabled}) を導入し
 * 三値論理 (グローバル × 組織) に拡張予定。詳細は設計書 §13。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "feature.shift-budget")
public class ShiftBudgetProperties {

    /** シフト予算機能の有効/無効フラグ（デフォルト false）。 */
    private boolean enabled = false;
}
