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
 *
 * <p>{@code bannerUrl} は生の R2 キーではなく、{@code MediaUrlResolver} で解決済みの
 * 署名付き表示 URL（絶対 URL）を返す（#2355 r2PublicUrl 根絶）。DTO に Spring 依存を
 * 持ち込まないため、URL 解決は呼出元（Service）が行い、解決済みの文字列をここへ渡すこと。</p>
 */
@Builder
public record FestivalResponse(
        UUID id,
        UUID villageId,
        String title,
        String description,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String bannerUrl,
        String themeColorHex,
        VillageFestivalStatus status,
        Long createdByUserId,
        String createdByDisplayName,
        LocalDateTime createdAt) {

    /**
     * エンティティ・表示名・解決済みバナー URL から DTO を生成する。
     *
     * @param entity              お祭りエンティティ
     * @param createdByDisplayName 作成者の表示名（null 可）
     * @param bannerUrl           {@code MediaUrlResolver} で解決済みのバナー表示 URL（未設定 / 解決失敗時は null）
     */
    public static FestivalResponse of(VillageFestivalEntity entity, String createdByDisplayName, String bannerUrl) {
        return FestivalResponse.builder()
                .id(entity.getId())
                .villageId(entity.getVillageId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .startsAt(entity.getStartsAt())
                .endsAt(entity.getEndsAt())
                .bannerUrl(bannerUrl)
                .themeColorHex(entity.getThemeColorHex())
                .status(entity.getStatus())
                .createdByUserId(entity.getCreatedByUserId())
                .createdByDisplayName(createdByDisplayName)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
