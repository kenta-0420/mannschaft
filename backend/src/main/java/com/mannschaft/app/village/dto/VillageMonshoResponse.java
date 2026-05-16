package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageEntity;

import java.util.UUID;

/**
 * 村紋（monsho）レスポンス DTO（F17 Phase 2 U7）。
 *
 * @param villageId 対象村 ID
 * @param monshoR2Key 村紋 R2 キー（削除済 / 未設定なら {@code null}）
 */
public record VillageMonshoResponse(
        UUID villageId,
        String monshoR2Key
) {
    public static VillageMonshoResponse from(VillageEntity entity) {
        return new VillageMonshoResponse(entity.getId(), entity.getMonshoR2Key());
    }
}
