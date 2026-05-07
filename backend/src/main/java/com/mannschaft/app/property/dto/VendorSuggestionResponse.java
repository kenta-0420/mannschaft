package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.entity.VendorEntity;

/**
 * 業者オートコンプリート用 軽量サジェストレスポンス（F09.13 Phase 1-δ）。
 *
 * <p>設計書 {@code docs/features/F09.13_property_history.md} §4「業者サジェスト 10件」
 * に対応。連絡先は含まず ID/名称/カナ/カテゴリのみ。</p>
 */
public record VendorSuggestionResponse(
        Long id,
        String name,
        String nameKana,
        VendorCategory category) {

    public static VendorSuggestionResponse from(VendorEntity entity) {
        return new VendorSuggestionResponse(
                entity.getId(),
                entity.getName(),
                entity.getNameKana(),
                entity.getCategory());
    }
}
