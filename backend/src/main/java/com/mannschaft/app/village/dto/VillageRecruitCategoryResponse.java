package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageRecruitCategoryEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ごと募集カテゴリのレスポンス（F17.1 P2 §6.2）。
 *
 * <p>{@code recruitCount} は使用中件数（削除可否の UI 表示用）。削除ガードと同じ集計方法
 * （生きている募集のみ）を用いるため、「使用数 0 と表示されているのに削除できない」という
 * 詰みは起きない（設計書 §6.2 の注）。</p>
 */
public record VillageRecruitCategoryResponse(
        UUID id,
        UUID villageId,
        String name,
        String description,
        String color,
        Integer displayOrder,
        boolean isPreset,
        long recruitCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static VillageRecruitCategoryResponse of(VillageRecruitCategoryEntity e, long recruitCount) {
        return new VillageRecruitCategoryResponse(
                e.getId(),
                e.getVillageId(),
                e.getName(),
                e.getDescription(),
                e.getColor(),
                e.getDisplayOrder(),
                Boolean.TRUE.equals(e.getIsPreset()),
                recruitCount,
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
