package com.mannschaft.app.shiftbudget.view;

/**
 * F08.7 シフト予算 API の Jackson {@code @JsonView} ビュー階層。
 *
 * <p>設計書 F08.7 (v1.2) §9.3 に準拠。3 階層のインタフェース継承で
 * {@code @JsonView(BudgetAdmin.class)} 適用時に Public/BudgetViewer のフィールドも含める。</p>
 *
 * <p>Phase 9-β スコープ:</p>
 * <ul>
 *   <li>{@link Public} — 一般ユーザー（金額・時給を全てマスク）</li>
 *   <li>{@link BudgetViewer} — {@code BUDGET_VIEW} 保有者（金額は見えるが個人別時給は見えない）</li>
 *   <li>{@link BudgetAdmin} — {@code BUDGET_ADMIN} 保有者（個人別時給まで全公開）。
 *       Phase 9-β 中は {@code by_user} を常に空配列で返却する運用のため API レイヤから直接利用しない。
 *       Phase 9-δ クリーンカット移行で BUDGET_ADMIN 単独判定に切り替わる際に活性化する</li>
 * </ul>
 */
public final class BudgetView {

    private BudgetView() {
    }

    /**
     * 一般ユーザー向け（金額・時給をマスクした状態）。
     */
    public interface Public {
    }

    /**
     * {@code BUDGET_VIEW} 保有者向け（金額閲覧可、個人別時給は不可）。
     */
    public interface BudgetViewer extends Public {
    }

    /**
     * {@code BUDGET_ADMIN} 保有者向け（個人別時給まで全公開）。
     *
     * <p>TODO(F08.7 Phase 9-δ): BUDGET_ADMIN クリーンカット移行時に
     * {@code ShiftBudgetAllocationController#consumptionSummary} へ {@code @JsonView(BudgetAdmin.class)} を
     * 切り替えて、{@code by_user} 配列を実データで返却するよう Service 側ロジックも併せて改修する。</p>
     */
    public interface BudgetAdmin extends BudgetViewer {
    }
}
