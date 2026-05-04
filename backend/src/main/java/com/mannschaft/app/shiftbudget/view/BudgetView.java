package com.mannschaft.app.shiftbudget.view;

/**
 * F08.7 シフト予算 API の Jackson {@code @JsonView} ビュー階層。
 *
 * <p>設計書 F08.7 (v1.2) §9.3 に準拠。3 階層のインタフェース継承で
 * {@code @JsonView(BudgetAdmin.class)} 適用時に Public/BudgetViewer のフィールドも含める。</p>
 *
 * <p>Phase 9-δ 第3段で本格化済。{@link com.mannschaft.app.shiftbudget.controller.ShiftBudgetSummaryController}
 * が {@code MappingJacksonValue} 経由で {@code BUDGET_ADMIN} 保有なら {@link BudgetAdmin}、
 * そうでなければ {@link BudgetViewer} を選択して serialize する。</p>
 *
 * <ul>
 *   <li>{@link Public} — 一般ユーザー（金額・時給を全てマスク）</li>
 *   <li>{@link BudgetViewer} — {@code BUDGET_VIEW} 保有者（金額は見えるが個人別時給は見えない）</li>
 *   <li>{@link BudgetAdmin} — {@code BUDGET_ADMIN} 保有者（個人別時給まで全公開）</li>
 * </ul>
 */
public final class BudgetView {

    private BudgetView() {
    }

    /**
     * 一般ユーザー向け（金額・時給をマスクした状態）。
     *
     * <p>含まれるフィールド: {@code allocation_id} / {@code status} / {@code flags}。
     * 金額系・個人別系は除外される。</p>
     */
    public interface Public {
    }

    /**
     * {@code BUDGET_VIEW} 保有者向け（金額閲覧可、個人別時給は不可）。
     *
     * <p>{@link Public} のフィールドに加え、配分額・消化額・残額・警告一覧などの集計値を含む。
     * {@code by_user} は除外される。</p>
     */
    public interface BudgetViewer extends Public {
    }

    /**
     * {@code BUDGET_ADMIN} 保有者向け（個人別時給まで全公開）。
     *
     * <p>{@link BudgetViewer} のフィールドに加え、{@code by_user} 個人別内訳を含む。
     * V11.034 マイグレーションにより既存 ADMIN/DEPUTY_ADMIN ロールには
     * {@code BUDGET_ADMIN} が自動付与済（Phase 9-δ 第1段）。</p>
     */
    public interface BudgetAdmin extends BudgetViewer {
    }
}
