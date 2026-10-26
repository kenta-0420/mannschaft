package com.mannschaft.app.reflection.dto;

import lombok.Builder;

/**
 * 一括アーカイブ結果レスポンス（F06.5 Phase 3・EP #21・§12.4）。
 *
 * @param archivedCount 一括アーカイブしたテーマ件数（0件でも 200 を返す）
 */
@Builder
public record BulkArchiveResult(
        int archivedCount
) {
}
