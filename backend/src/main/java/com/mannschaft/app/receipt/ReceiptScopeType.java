package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;

import java.util.Locale;

/**
 * 領収書のスコープ種別。
 */
public enum ReceiptScopeType {
    ORGANIZATION,
    TEAM;

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
