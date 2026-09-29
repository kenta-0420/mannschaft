package com.mannschaft.app.billing;

/** 価格 revision / band のライフサイクル状態。 */
public enum BillingPriceVersionStatus {
    DRAFT,
    PROVISIONING,
    PROVISION_FAILED,
    READY,
    SCHEDULED,
    ACTIVE,
    RETIRED,
    /**
     * 取り消し済み（2026-09-24 御裁可）。DRAFT / READY / PROVISION_FAILED から {@code POST .../cancel} で遷移する
     * 終端状態。future 枠（{@code uk_bpv_single_future}）を占有せず、販売経路にも乗らない。
     */
    CANCELLED
}
