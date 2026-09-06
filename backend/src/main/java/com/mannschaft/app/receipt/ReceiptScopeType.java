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

    /**
     * テナントスコープ（{@code ORGANIZATION} / {@code TEAM}）限定でスコープ種別を解決する。
     *
     * <p>{@link #from(String)} との違いは {@code PLATFORM} を受け付けない点である。
     * 運営スコープの領収書 API は {@code PlatformReceiptController}（SYSTEM_ADMIN 限定・
     * {@code checkAdminOrAboveIncludingPlatform} 経由）が担い、テナント向けの管理 API は
     * membership の {@code ScopeType} を前提に認可する。テナント API に {@code PLATFORM} が
     * 届くと {@code AccessControlService#isMember} 内の {@code ScopeType.valueOf("PLATFORM")}
     * が {@link IllegalArgumentException} となり 500 になるため、入口で 400（COMMON_001）に
     * 落とす。</p>
     *
     * @param value クエリで受け取った文字列（大文字小文字を問わない）
     * @return テナントスコープ種別
     */
    public static ReceiptScopeType fromTenantScope(String value) {
        ReceiptScopeType scopeType = from(value);
        if (scopeType.isPlatform()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return scopeType;
    }
}
