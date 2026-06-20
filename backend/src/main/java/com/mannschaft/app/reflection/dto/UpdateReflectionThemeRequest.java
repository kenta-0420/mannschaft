package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.ReflectionSourceType;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * テーマ更新リクエスト（F06.5・§7 #4・exam_date 設定含む）。
 *
 * <p>部分更新（null=現値維持セマンティクス）。{@code examDateCleared=true} で考査日を明示クリアする
 * （PATCH における「null＝未指定」と「null＝消去」の曖昧さを回避）。</p>
 *
 * @param title          新テーマ名（null なら現値維持）
 * @param description    新説明（null なら現値維持）
 * @param sourceType     新 source_type（null なら現値維持）
 * @param examDate       新考査日（null かつ examDateCleared=false なら現値維持）
 * @param examDateCleared true なら examDate を NULL にクリアする
 */
public record UpdateReflectionThemeRequest(

        @Size(max = 120, message = "テーマ名は120文字以内で入力してください")
        String title,

        @Size(max = 500, message = "説明は500文字以内で入力してください")
        String description,

        ReflectionSourceType sourceType,

        LocalDate examDate,

        boolean examDateCleared
) {
}
