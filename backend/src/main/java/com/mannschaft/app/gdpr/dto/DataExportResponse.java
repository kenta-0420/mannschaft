package com.mannschaft.app.gdpr.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * データエクスポートレスポンスDTO。
 * エクスポートジョブのステータスと結果情報を返す。
 */
@Getter
@Builder
public class DataExportResponse {

    /** エクスポートID */
    private final Long exportId;

    /** ステータス（PENDING / PROCESSING / COMPLETED / FAILED） */
    private final String status;

    /** 進捗パーセント（0〜100） */
    private final Integer progressPercent;

    /** 現在のステップ名 */
    private final String currentStep;

    /** ファイルサイズバイト数（COMPLETEDのみ） */
    private final Long fileSizeBytes;

    /** ダウンロードURLの有効期限（COMPLETEDのみ） */
    private final LocalDateTime expiresAt;

    /** エクスポートリクエスト日時 */
    private final LocalDateTime createdAt;

    /** 処理完了日時（COMPLETEDまたはFAILEDのみ） */
    private final LocalDateTime completedAt;
}
