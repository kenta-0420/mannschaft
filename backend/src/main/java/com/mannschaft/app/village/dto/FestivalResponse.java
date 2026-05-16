package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.1 Phase 2 U5 — 村お祭りレスポンス。
 *
 * <p>{@code createdByDisplayName} は呼出元（Service）で解決して詰める。
 * Phase 2 では USER の表示名解決は B4 ニックネーム連携で行うが、本 DTO は
 * 表示名の中身に関知せず文字列をそのまま返す。</p>
 */
@Builder
public record FestivalResponse(
        UUID id,
        UUID villageId,
        String title,
        String description,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String bannerR2Key,
        String themeColorHex,
        VillageFestivalStatus status,
        Long createdByUserId,
        String createdByDisplayName,
        LocalDateTime createdAt) {

    /**
     * エンティティと表示名から DTO を生成する。
     *
     * @param entity              お祭りエンティティ
     * @param createdByDisplayName 作成者の表示名（null 可）
     */
    public static FestivalResponse of(VillageFestivalEntity entity, String createdByDisplayName) {
        return FestivalResponse.builder()
                .id(entity.getId())
                .villageId(entity.getVillageId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .startsAt(entity.getStartsAt())
                .endsAt(entity.getEndsAt())
                .bannerR2Key(entity.getBannerR2Key())
                .themeColorHex(entity.getThemeColorHex())
                .status(entity.getStatus())
                .createdByUserId(entity.getCreatedByUserId())
                .createdByDisplayName(createdByDisplayName)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
