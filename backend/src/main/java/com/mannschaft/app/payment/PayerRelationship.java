package com.mannschaft.app.payment;

/**
 * 払い手と受益者の関係スナップショット。
 *
 * <p>支払い時の関係性を {@link com.mannschaft.app.payment.entity.MemberPaymentEntity} に
 * スナップショットとして保存し、監査・表示用途に使う。
 * 受益者（beneficiary）視点での分類であり、支払い後に関係が変わっても記録は変更しない。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §1.1</p>
 */
public enum PayerRelationship {

    /** 受益者本人が支払い。 */
    SELF,

    /** 法定後見人（保護者）が支払い。parental_consent_links / user_care_links による権原。 */
    GUARDIAN,

    /**
     * 後見切替セッション中の代理払い。
     * 法定後見人が後見切替操作中に代理で実行した支払いを示す。
     */
    GUARDIAN_PROXY,

    /**
     * payment_proxy_grants による第三者払い（非後見・祖父母・スポンサー等）。
     * payment_proxy_grant_id が必ず設定される。
     */
    PROXY_GRANT,

    /** 管理者（ADMIN）が手動で記録した支払い。監査・確認用。 */
    ADMIN_MANUAL
}
