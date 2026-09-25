package com.mannschaft.app.billing.api.dto;

/**
 * {@code provision}/{@code retry-provision}/{@code reconcile-provision}/{@code activate} の
 * CAS 期待値。省略時（本文なし・{@code {}}）は 0 として扱う（作成直後の revision の
 * 既定 {@code lockVersion} と一致する）。
 */
public record PriceRevisionLockVersionRequest(long lockVersion) {
    public PriceRevisionLockVersionRequest() {
        this(0L);
    }
}
