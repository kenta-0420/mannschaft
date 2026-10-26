package com.mannschaft.app.billing;

import java.time.LocalDateTime;

/**
 * Billing Center PR6a: 「解約できるか／撤回できるか」の導出を一点に閉じるための純関数群（AC-60 / AC-63）。
 *
 * <p>同じ判定が解約 API の応答（{@code BillingContractCancelService.CancelView}）と権利サマリの
 * 投影（{@code BillingEntitlementQueryService}）の 2 箇所で必要になる。別々に書くと必ず食い違うため、
 * <b>導出はこのクラスだけが持つ</b>。</p>
 *
 * <p><b>Stripe を呼ばない</b>（AC-63。正本 05:369「表示では Stripe 同期呼出しをせず投影を読む」）。
 * 判定に使うのは DB の {@code status} / {@code cancelled_at} / {@code current_period_end} と現在時刻だけである。</p>
 */
public final class BillingCancelState {

    private BillingCancelState() {
    }

    /**
     * 利用者が操作できる状態か（{@code ACTIVE} / {@code PAST_DUE}）。
     *
     * <p>{@code PAST_DUE} を含めるのは殿の設計判断 D4（支払失敗中でも期末解約はできる）による。</p>
     *
     * @param status 契約状態
     * @return 操作可能なら true
     */
    public static boolean operable(ContractStatus status) {
        return status == ContractStatus.ACTIVE || status == ContractStatus.PAST_DUE;
    }

    /**
     * 期末解約が「予約されている」か。
     *
     * <p>既に {@code CANCELLED} / {@code EXPIRED} へ確定した契約の {@code cancelled_at} は
     * 「解約済みの記録」であって「これから期末に解約される予約」ではない。予約として扱うのは
     * 操作可能な状態のときだけである（AC-23）。</p>
     *
     * @param status      契約状態
     * @param cancelledAt {@code billing_contracts.cancelled_at}
     * @return 解約予約中なら true
     */
    public static boolean scheduled(ContractStatus status, LocalDateTime cancelledAt) {
        return operable(status) && cancelledAt != null;
    }

    /**
     * 解約できるか（操作可能かつ未予約）。
     *
     * @param status      契約状態
     * @param cancelledAt {@code cancelled_at}
     * @return 解約できるなら true
     */
    public static boolean canCancel(ContractStatus status, LocalDateTime cancelledAt) {
        return operable(status) && !scheduled(status, cancelledAt);
    }

    /**
     * 撤回できるか（解約予約中で、かつ期末をまだ跨いでいない・AC-46）。
     *
     * @param status      契約状態
     * @param cancelledAt {@code cancelled_at}
     * @param endAt       期末（{@code current_period_end}）
     * @param now         現在時刻（注入 Clock 由来）
     * @return 撤回できるなら true
     */
    public static boolean canResume(ContractStatus status, LocalDateTime cancelledAt,
                                    LocalDateTime endAt, LocalDateTime now) {
        return scheduled(status, cancelledAt) && endAt != null && endAt.isAfter(now);
    }
}
