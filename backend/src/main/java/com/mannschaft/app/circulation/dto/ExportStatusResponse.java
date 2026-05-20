package com.mannschaft.app.circulation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 押印済み証跡 PDF エクスポートの生成状況レスポンス。
 *
 * <p>F05.2 Phase 11 第四陣 4-C で追加。
 * {@code GET /api/v1/circulations/{documentId}/export/status} の返却 DTO。</p>
 *
 * @param documentId    文書 ID
 * @param status        エクスポートステータス（NOT_GENERATED / PENDING / COMPLETED / FAILED）
 * @param requestedAt   生成リクエスト受付時刻
 * @param completedAt   生成完了時刻（COMPLETED の場合のみセット）
 * @param errorMessage  エラーメッセージ（FAILED の場合のみセット）
 * @param url           Pre-signed ダウンロード URL（COMPLETED の場合のみ、有効期限 1h）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportStatusResponse(
        Long documentId,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        String errorMessage,
        String url
) {
}
