package com.mannschaft.app.billing;

/** 価格 revision / band のライフサイクル状態。 */
public enum BillingPriceVersionStatus {
    DRAFT,
    PROVISIONING,
    PROVISION_FAILED,
    READY,
    SCHEDULED,
    ACTIVE,
    RETIRED
}
