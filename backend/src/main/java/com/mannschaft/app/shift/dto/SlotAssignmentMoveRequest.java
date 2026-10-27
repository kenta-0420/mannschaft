package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 枠間移動リクエスト DTO（CMP-261010-1432 P）。
 *
 * <p>パスの {@code slotId} が移動元。移動先は同じシフト表の別の枠であること。</p>
 *
 * @param targetSlotId      移動先の枠 ID
 * @param userId            移動するメンバーのユーザー ID
 * @param sourceSlotVersion 移動元の枠の現行 version（楽観ロック）
 * @param targetSlotVersion 移動先の枠の現行 version（楽観ロック）
 */
public record SlotAssignmentMoveRequest(
        @NotNull Long targetSlotId,
        @NotNull Long userId,
        @NotNull Long sourceSlotVersion,
        @NotNull Long targetSlotVersion
) {}
