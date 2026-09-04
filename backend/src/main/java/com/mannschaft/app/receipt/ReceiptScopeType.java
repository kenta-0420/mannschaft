package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;

import java.util.Locale;

/**
 * 領収書のスコープ種別。
 */
public enum ReceiptScopeType {
    ORGANIZATION,
    TEAM,

    /**
     * Mannschaft 運営（プラットフォーム事業者）自身が発行者となるスコープ（F08.12）。
     *
     * <p>{@code scope_id} には実テナントが存在しないため
     * {@link ReceiptScopes#PLATFORM_SCOPE_ID}（0）を用いる。</p>
     *
     * <p>DDL 上の {@code scope_type} は {@code VARCHAR(20)}（ENUM ではない）ため、
     * 値の追加にマイグレーションは不要である。</p>
     */
    PLATFORM;

    /** 運営（プラットフォーム）スコープかどうか。 */
    public boolean isPlatform() {
        return this == PLATFORM;
    }

    /**
     * クエリ文字列から安全にスコープ種別を解決する（F08.4 §9.1.1 D-5）。
     *
     * <p>{@code valueOf} を直接呼ぶと未知値が {@link IllegalArgumentException} となり 500 に
     * なるため、業務例外（400 / COMMON_001）へ変換する。大文字化は {@link Locale#ROOT}
     * 固定とし、トルコ語ロケールで {@code i → İ} となる事故を防ぐ。</p>
     *
     * @param value クエリで受け取った文字列（大文字小文字を問わない）
     * @return スコープ種別
     */
    public static ReceiptScopeType from(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.COMMON_001, e);
        }
    }
}
