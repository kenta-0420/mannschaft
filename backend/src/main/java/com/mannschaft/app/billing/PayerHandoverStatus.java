package com.mannschaft.app.billing;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538）: {@code billing_payer_handover_requests.status} の
 * 状態機械（VARCHAR(24) + CHECK・10値。PR-4 で FAILING_CLEANUP を追加）。
 *
 * <p>設計書: {@code docs/architecture/billing_payer_handover_design.md} §3.1・§3.6・§3.6.2・§4.2。
 * {@code billing_contracts.status}（契約レベル）とは別物であり、本 enum は「引継要求」自体の進行状況を表す。</p>
 *
 * <h2>状態遷移（§4.2 遷移表）</h2>
 * <ul>
 *   <li>{@code REQUESTED} → {@code ACCEPTED} / {@code EXPIRED} / {@code FAILED}（退会取消時）</li>
 *   <li>{@code ACCEPTED} → {@code REQUIRES_PAYMENT_METHOD} / {@code SWITCHING}</li>
 *   <li>{@code REQUIRES_PAYMENT_METHOD} → {@code ACCEPTED}（再検証） / {@code EXPIRED}</li>
 *   <li>{@code SWITCHING} → {@code COMPLETED} / {@code PARTIALLY_COMPLETED} / {@code FAILED} /
 *       {@code MANUAL_INTERVENTION}</li>
 *   <li>{@code PARTIALLY_COMPLETED}（非終端） → {@code COMPLETED}</li>
 *   <li>{@code MANUAL_INTERVENTION}（非終端） → {@code SWITCHING}（RESUME→切替再試行） /
 *       {@code FAILED}（RESUME→FAILED確定）</li>
 *   <li>{@code COMPLETED} / {@code FAILED} / {@code EXPIRED}（終端）</li>
 * </ul>
 *
 * <p>終端状態（{@code COMPLETED}/{@code FAILED}/{@code EXPIRED}）のみが
 * {@code open_old_contract_id} 生成列で NULL になる。{@code PARTIALLY_COMPLETED}・
 * {@code MANUAL_INTERVENTION}・{@code FAILING_CLEANUP} は非終端として扱い、値を保持し続ける
 * （§3.5・§3.6.2）。</p>
 */
public enum PayerHandoverStatus {
    /** 通知済み・未承諾（初期状態）。 */
    REQUESTED,
    /** 承諾済み・PaymentMethod検証前。 */
    ACCEPTED,
    /** PaymentMethod未登録で差し戻し中。 */
    REQUIRES_PAYMENT_METHOD,
    /** 旧期末到達待ち・旧サブスクは cancel_at_period_end=true で確定済み。 */
    SWITCHING,
    /** 非終端。Stripe側は確定済み・ローカルの切替TXのみ未了（夜次バッチのリトライ対象）。 */
    PARTIALLY_COMPLETED,
    /** 非終端。自動処理では安全に解消できない異常を検知し運用者の RESUME 待ち（§3.6.2）。 */
    MANUAL_INTERVENTION,
    /**
     * <b>非終端</b>。失敗確定は決まったが、Stripe 側の後始末
     * （新 trial サブスクの即時取消・旧サブスクの {@code cancel_at_period_end} 差し戻し）が未了
     * （PR-4 Codex 検分4巡目・V206）。
     *
     * <p>後始末は外部システムへの呼び出しであり DB トランザクションに巻き込めない。
     * 「未了」を目印列の有無で表すと、目印を消した瞬間に
     * ①夜次バッチの抽出から外れて回収不能になり ②生成列 {@code open_old_contract_id} 上の終端に
     * なるため UNIQUE 枠が空いて<b>通常の作成入口から次の要求を作れてしまう</b>
     * （＝旧試行のサブスクが Stripe に残ったまま新しい承諾が進み二重サブスクになり得る）。
     * 非終端の状態として持つことで、その2つの防御が既存の仕組みだけで自動的に成立する。</p>
     *
     * <p>出口は {@code FAILED}（後始末の成功を確認した後の終端化）のみ。</p>
     */
    FAILING_CLEANUP,
    /** 切替完了（終端）。 */
    COMPLETED,
    /** 失敗（終端。旧契約は原則無傷）。 */
    FAILED,
    /** 期限切れ（終端）。 */
    EXPIRED
}
