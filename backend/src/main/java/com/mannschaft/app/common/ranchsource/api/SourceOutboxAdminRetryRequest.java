package com.mannschaft.app.common.ranchsource.api;

/** 本文や自由記述を受付けず、有限長の技術的理由コードだけを持つ。 */
public record SourceOutboxAdminRetryRequest(String reasonCode) {
    public SourceOutboxAdminRetryRequest {
        if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException("再処理理由コードが不正です");
        }
    }
}
