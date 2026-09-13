package com.mannschaft.app.shift.dto;

import java.util.List;

/**
 * 手動割当の警告 DTO（設計 F03.5 §11.3.5）。
 *
 * <p>既存の {@link AssignmentWarningDto}（自動割当用）を流用しないのは、
 * あちらが単一の {@code slotId} しか持たず、本警告が返す必要のある
 * <b>衝突相手の枠 ID 一覧</b>を表現できないためである。既存 DTO にフィールドを足すと
 * 自動割当のレスポンス契約まで変わるので、別 record として新設する。</p>
 *
 * @param code                警告コード（現状 {@link #ASSIGNMENT_OVERLAP} のみ）
 * @param conflictingSlotIds  衝突している相手の枠 ID（昇順・重複なし）
 */
public record ShiftAssignmentWarningDto(
        String code,
        List<Long> conflictingSlotIds
) {

    /**
     * 同一人物の勤務時間が重なっている旨の警告コード。
     *
     * <p>これは<b>エラーコードではない</b>（保存は成功し 200 が返る）ため
     * {@code ShiftErrorCode} には置かず、警告 DTO 自身が定数として持つ。</p>
     */
    public static final String ASSIGNMENT_OVERLAP = "ASSIGNMENT_OVERLAP";
}
