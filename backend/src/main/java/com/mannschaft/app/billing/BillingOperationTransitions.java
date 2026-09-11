package com.mannschaft.app.billing;

import java.util.Set;

/**
 * Billing Center PR6a: 契約操作 Saga の状態機械（AC-10 / AC-11）。
 *
 * <p><b>本クラスは第2隊（試練A）が置いた発注書であり、中身は未実装である。</b>
 * 第5隊（Saga Service）が {@link UnsupportedOperationException} を実装で置き換える。
 * 期待する振る舞いは {@code BillingOperationStateMachineTest} が AC 番号つきで固定している。</p>
 *
 * <h2>許可する辺（AC-10・これ以外は全て拒否する）</h2>
 * <pre>
 * CREATED                 -&gt; CALLING_STRIPE
 * CREATED                 -&gt; CANCELLED                （D8 停止窓(a) の回収）
 * CALLING_STRIPE          -&gt; APPLIED | FAILED | RECONCILIATION_REQUIRED | CANCELLED
 * RECONCILIATION_REQUIRED -&gt; APPLIED | FAILED | CANCELLED （reconcile による確定）
 * </pre>
 *
 * <p>逆行（terminal からの遷移・{@code CALLING_STRIPE -> CREATED} 等）と飛び越し
 * （{@code CREATED -> APPLIED} 等）は例外で拒否する。自己遷移も拒否する
 * （冪等な再実行は「遷移しない」で表現し、状態機械の辺としては許さない）。</p>
 */
public final class BillingOperationTransitions {

    private BillingOperationTransitions() {
    }

    /** terminal（pointer を同一トランザクションで解放してよい状態・AC-7）。 */
    public static final Set<BillingOperationStatus> TERMINAL = Set.of(
            BillingOperationStatus.APPLIED,
            BillingOperationStatus.FAILED,
            BillingOperationStatus.CANCELLED);

    /**
     * 状態遷移が許可された辺かを判定する（AC-10）。
     *
     * @param from 遷移元
     * @param to   遷移先
     * @return 許可された辺なら true
     */
    public static boolean isAllowed(BillingOperationStatus from, BillingOperationStatus to) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * 許可されていない辺なら例外で拒否する（AC-10）。
     *
     * @param from 遷移元
     * @param to   遷移先
     * @throws IllegalStateException 許可されていない辺のとき
     */
    public static void requireAllowed(BillingOperationStatus from, BillingOperationStatus to) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * terminal 判定（AC-7・AC-8）。{@link BillingOperationStatus#RECONCILIATION_REQUIRED} は
     * terminal では<b>ない</b>検疫状態である。
     *
     * @param status 判定対象
     * @return terminal なら true
     */
    public static boolean isTerminal(BillingOperationStatus status) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * kind と status から {@code step}（VARCHAR(32) NOT NULL）の値を決める（AC-11）。
     *
     * @param kind   操作種別
     * @param status 操作状態
     * @return 格納すべき step
     */
    public static BillingOperationStep stepFor(
            BillingOperationKind kind, BillingOperationStatus status) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }
}
