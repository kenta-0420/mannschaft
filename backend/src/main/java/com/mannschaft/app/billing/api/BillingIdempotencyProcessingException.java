package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.common.BusinessException;

/**
 * BC-23: 同一 {@code Idempotency-Key} の先行要求が処理中（lease 有効）であることを表す業務例外。
 *
 * <p>設計（05_billing_center.md §「処理中かつlease有効なら202又は409に {@code Retry-After} を付け」）に従い、
 * 409 と {@code Retry-After} 秒を返す。既存応答本文・lease 所有者は一切外へ出さない。</p>
 */
public class BillingIdempotencyProcessingException extends BusinessException {

    private final long retryAfterSeconds;

    public BillingIdempotencyProcessingException(long retryAfterSeconds) {
        super(EntitlementErrorCode.CHANGE_CONFLICT);
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
    }

    /** {@code Retry-After} ヘッダへ載せる秒数。 */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
