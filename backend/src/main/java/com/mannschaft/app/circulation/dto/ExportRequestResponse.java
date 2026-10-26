package com.mannschaft.app.circulation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 押印済み証跡 PDF エクスポートのリクエスト受付レスポンス。
 *
 * <p>F05.2 Phase 11 第四陣 4-C で追加。
 * 202 Accepted で返却される、非同期生成ジョブの受付確認用 DTO。</p>
 *
 * @param documentId 文書 ID
 * @param status     エクスポートステータス（GENERATING / FAILED 等）
 * @param pollUrl    生成状況確認用 URL
 * @param estimatedSeconds 生成完了見込み時間（秒）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportRequestResponse(
        Long documentId,
        String status,
        String pollUrl,
        Integer estimatedSeconds
) {
}
