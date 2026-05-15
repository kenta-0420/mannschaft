package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * グループ詳細レスポンス（カード復号値含む）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2 / §6 (Groups API)
 *
 * <p>提示モード起動時に呼ばれ、グループに含まれる全カードの復号値（暗号化フィールド含む）を
 * 1 リクエストでまとめて返す。N+1 回避のため Service 内で JPQL コンストラクタ式 + マップで
 * 取得する（{@code findAllByGroupIdOrderByDisplayOrderAsc} + Provider 一括取得）。
 */
public record GroupDetailResponse(
        UUID id,
        String name,
        String emoji,
        int displayOrder,
        List<GroupItemResponse> items,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static GroupDetailResponse from(PointCardGroupEntity group, List<GroupItemResponse> items) {
        return new GroupDetailResponse(
                group.getId(),
                group.getName(),
                group.getEmoji(),
                group.getDisplayOrder(),
                items,
                group.getCreatedAt(),
                group.getUpdatedAt()
        );
    }
}
