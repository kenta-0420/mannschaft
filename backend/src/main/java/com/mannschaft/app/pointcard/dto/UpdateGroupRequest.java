package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * グループ更新リクエスト（PATCH）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 (Groups API)
 *
 * <p>全フィールド optional。null は「変更なし」を意味する差分更新セマンティクス。
 * {@code cardIds} を指定した場合は既存アイテムを差し替える（追加だけでなく削除も含む）。
 */
public record UpdateGroupRequest(
        @Size(max = 64)
        String name,

        @Size(max = 8)
        String emoji,

        Integer displayOrder,

        @Size(max = 20)
        List<UUID> cardIds
) {
}
