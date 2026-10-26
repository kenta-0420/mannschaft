package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カテゴリ削除レスポンス DTO。
 *
 * <p>設計書 F05.1 §4「DELETE /api/v1/bulletin/categories/{id}」に準拠。
 * カテゴリ削除に伴い未分類（category_id = NULL）へ移行したスレッド件数を返す。</p>
 */
@Getter
@RequiredArgsConstructor
public class DeleteCategoryResponse {

    /** 削除したカテゴリ ID。 */
    private final Long id;

    /** 未分類へ移行したスレッド件数。 */
    private final int affectedThreadCount;

    /** 処理結果メッセージ。 */
    private final String message;
}
