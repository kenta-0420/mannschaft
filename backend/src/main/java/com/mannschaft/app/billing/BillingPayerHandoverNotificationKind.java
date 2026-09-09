package com.mannschaft.app.billing;

/**
 * 柱③-B 請求担当引継（CMP-260901-1538）: 引継フローが送る通知の種別。
 *
 * <p>設計書 {@code docs/architecture/billing_payer_handover_design.md} §5.2・§5.5・§3.6。
 * 通知は必ず業務トランザクション内で {@link BillingPayerHandoverNotificationEvent} を
 * {@code publishEvent} するだけに留め、実配送は {@code AFTER_COMMIT} リスナー
 * （{@link BillingPayerHandoverNotificationListener}）が行う。</p>
 */
public enum BillingPayerHandoverNotificationKind {

    /** 引継要求が発行された（対象スコープの他 ADMIN 全員へ・設計書 §5.2）。 */
    HANDOVER_REQUESTED,

    /**
     * 承諾が支払い手段未登録で差し戻された（設計書 §3.6 二段検証の1段目・AC-16/AC-19）。
     * 宛先は承諾を試みた ADMIN 本人。
     */
    PAYMENT_METHOD_REQUIRED,

    /**
     * 新サブスクに {@code pending_setup_intent} が残っており追加認証（SCA/3DS）が必要
     * （設計書 §3.6 二段検証の1段目・AC-30）。<b>この通知は状態遷移を伴わない</b>。
     */
    ADDITIONAL_AUTH_REQUIRED,

    /**
     * 引継が {@code MANUAL_INTERVENTION} へ倒れ、運用者・管理者の手動対応が必要になった
     * （設計書 §3.6.2「アラート先」・AC-35/AC-36・PR-4）。
     *
     * <p>宛先は当該スコープの引継先候補 ADMIN。この状態は<b>非終端</b>であり、
     * 誰も気づかなければ引継が止まったまま旧契約への課金が続くため、必ず通知する。</p>
     */
    MANUAL_INTERVENTION_REQUIRED
}
