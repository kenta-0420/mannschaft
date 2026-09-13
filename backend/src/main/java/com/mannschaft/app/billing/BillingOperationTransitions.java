package com.mannschaft.app.billing;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Billing Center PR6a: 契約操作 Saga の状態機械（AC-10 / AC-11）。
 *
 * <p>期待する振る舞いは {@code BillingOperationStateMachineTest} が AC 番号つきで固定している。</p>
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
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED_EDGES.getOrDefault(from, EnumSet.noneOf(BillingOperationStatus.class))
                .contains(to);
    }

    /**
     * 許可されていない辺なら例外で拒否する（AC-10）。
     *
     * @param from 遷移元
     * @param to   遷移先
     * @throws IllegalStateException 許可されていない辺のとき
     */
    public static void requireAllowed(BillingOperationStatus from, BillingOperationStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalStateException(
                    "operation の状態遷移が許可されていない: " + from + " -> " + to);
        }
    }

    /**
     * terminal 判定（AC-7・AC-8）。{@link BillingOperationStatus#RECONCILIATION_REQUIRED} は
     * terminal では<b>ない</b>検疫状態である。
     *
     * @param status 判定対象
     * @return terminal なら true
     */
    public static boolean isTerminal(BillingOperationStatus status) {
        return status != null && TERMINAL.contains(status);
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
        if (kind == null || status == null) {
            throw new IllegalArgumentException("kind / status は必須である");
        }
        return switch (status) {
            case CREATED -> BillingOperationStep.RECEIVED;
            case CALLING_STRIPE -> CALLING_STRIPE_STEPS.get(kind);
            case RECONCILIATION_REQUIRED -> BillingOperationStep.RECONCILE_PENDING;
            case APPLIED -> BillingOperationStep.FINALIZED;
            case FAILED, CANCELLED -> BillingOperationStep.ABORTED;
        };
    }

    /** 許可する辺（AC-10）。ここに無い組合せは全て拒否する（自己遷移・terminal からの離脱を含む）。 */
    private static final Map<BillingOperationStatus, Set<BillingOperationStatus>> ALLOWED_EDGES =
            buildAllowedEdges();

    /** {@code CALLING_STRIPE} 中の step（kind 固有・AC-11）。 */
    private static final Map<BillingOperationKind, BillingOperationStep> CALLING_STRIPE_STEPS =
            buildCallingStripeSteps();

    private static Map<BillingOperationStatus, Set<BillingOperationStatus>> buildAllowedEdges() {
        Map<BillingOperationStatus, Set<BillingOperationStatus>> edges =
                new EnumMap<>(BillingOperationStatus.class);
        edges.put(BillingOperationStatus.CREATED, EnumSet.of(
                BillingOperationStatus.CALLING_STRIPE,
                BillingOperationStatus.CANCELLED));
        edges.put(BillingOperationStatus.CALLING_STRIPE, EnumSet.of(
                BillingOperationStatus.APPLIED,
                BillingOperationStatus.FAILED,
                BillingOperationStatus.RECONCILIATION_REQUIRED,
                BillingOperationStatus.CANCELLED));
        edges.put(BillingOperationStatus.RECONCILIATION_REQUIRED, EnumSet.of(
                BillingOperationStatus.APPLIED,
                BillingOperationStatus.FAILED,
                BillingOperationStatus.CANCELLED));
        return edges;
    }

    private static Map<BillingOperationKind, BillingOperationStep> buildCallingStripeSteps() {
        Map<BillingOperationKind, BillingOperationStep> steps =
                new EnumMap<>(BillingOperationKind.class);
        steps.put(BillingOperationKind.CANCEL, BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION);
        steps.put(BillingOperationKind.RESUME, BillingOperationStep.STRIPE_RESUME_SUBSCRIPTION);
        steps.put(BillingOperationKind.DOWNGRADE_TO_CANCEL,
                BillingOperationStep.STRIPE_SCHEDULE_DOWNGRADE);
        steps.put(BillingOperationKind.PLAN_CHANGE, BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE);
        steps.put(BillingOperationKind.MIGRATION, BillingOperationStep.STRIPE_MIGRATION_SETUP);
        steps.put(BillingOperationKind.MEMBER_REPRICE, BillingOperationStep.STRIPE_REPRICE_SCHEDULE);
        steps.put(BillingOperationKind.REFUND, BillingOperationStep.STRIPE_REFUND_ISSUE);
        return steps;
    }
}
