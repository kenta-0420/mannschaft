package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * グループ一覧の軽量 DTO（カード詳細を含まない）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 (Groups API)
 */
public record GroupListItemResponse(
        UUID id,
        String name,
        String emoji,
        int displayOrder,
        long cardCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static GroupListItemResponse from(PointCardGroupEntity group, long cardCount) {
        return new GroupListItemResponse(
                group.getId(),
                group.getName(),
                group.getEmoji(),
                group.getDisplayOrder(),
                cardCount,
                group.getCreatedAt(),
                group.getUpdatedAt()
        );
    }
}
