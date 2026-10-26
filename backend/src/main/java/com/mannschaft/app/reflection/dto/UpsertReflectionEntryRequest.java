package com.mannschaft.app.reflection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * エントリ upsert リクエスト（F06.5・§7 #7）。
 *
 * <p>(theme,target_date) 一意・楽観排他。{@code expectedVersion} は既存更新時必須／新規作成時 null（§4.3）。
 * {@code visibility} は MVP 受け付けず PRIVATE 固定。structured_content は §2.3 スキーマ（サニタイズはサービス層）。</p>
 *
 * @param themeId           テーマID（必須）
 * @param targetDate        振り返り対象日（必須・過去365〜未来30日。範囲検証はサービス層・§2.5.1(c)）
 * @param structuredContent アウトライン構造 JSON（必須・§2.3）
 * @param expectedVersion   楽観排他の期待バージョン（新規時 null・既存更新時必須）
 */
public record UpsertReflectionEntryRequest(

        @NotNull(message = "テーマIDを指定してください")
        UUID themeId,

        @NotNull(message = "対象日を指定してください")
        LocalDate targetDate,

        @NotNull(message = "振り返り内容を入力してください")
        JsonNode structuredContent,

        Long expectedVersion
) {
}
