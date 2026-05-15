package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * グループ作成リクエスト。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 (Groups API)
 *
 * <p>{@code cardIds} は任意。空 / null の場合は空グループとして作成し、後で
 * {@code PATCH /groups/{id}} でカードを追加できる。最大 20 件まで指定可能で、
 * 上限超過は {@code GROUP_ITEM_LIMIT_EXCEEDED} (409) を返す。
 */
public record CreateGroupRequest(
        @NotBlank
        @Size(max = 64)
        String name,

        @Size(max = 8)
        String emoji,

        @Size(max = 20)
        List<UUID> cardIds
) {
}
