package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * スロット差分割当リクエストDTO。
 *
 * @param addUserIds    追加するユーザーIDリスト
 * @param removeUserIds 削除するユーザーIDリスト
 * @param slotVersion   楽観ロック用スロットバージョン
 */
public record SlotAssignmentPatchRequest(
        List<Long> addUserIds,
        List<Long> removeUserIds,
        @NotNull Integer slotVersion
) {}
