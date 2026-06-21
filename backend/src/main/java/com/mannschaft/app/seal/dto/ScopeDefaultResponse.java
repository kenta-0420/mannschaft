package com.mannschaft.app.seal.dto;

import com.mannschaft.app.seal.SealVariant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スコープデフォルトレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ScopeDefaultResponse {

    private final Long id;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;
    /**
     * スコープ表示名。
     * DEFAULT="デフォルト" / TEAM=チーム名 / ORGANIZATION=組織名 /
     * 解決不能の場合は "不明なチーム" / "不明な組織"。
     */
    private final String scopeName;
    private final Long sealId;
    /**
     * 印鑑字体種別。印鑑が削除済みの場合は null。
     */
    private final SealVariant variant;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
