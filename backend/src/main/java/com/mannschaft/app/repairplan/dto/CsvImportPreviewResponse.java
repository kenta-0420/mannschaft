package com.mannschaft.app.repairplan.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 修繕計画項目 CSV インポート プレビューレスポンス（dry_run）。
 *
 * <p>Valkey に保存された CSV プレビューの一意キー（{@code importToken}）と
 * 行単位の検証結果を返す。クライアントは内容を確認したうえで confirm エンドポイントに
 * {@code importToken} を渡してインポートを確定する。</p>
 */
public record CsvImportPreviewResponse(
        String importToken,
        LocalDateTime expiresAt,
        int totalRows,
        int validRows,
        int errorRows,
        List<CsvRowPreview> preview,
        List<CsvImportError> errors
) {

    /** プレビュー行（バリデーション前後の表示用） */
    public record CsvRowPreview(
            int rowNumber,
            String category,
            String title,
            String description,
            String plannedYear,
            String plannedMonth,
            String estimatedAmount,
            String cpiInflationBasisYear,
            String status,
            String tags,
            boolean valid,
            String errorMessage
    ) {
    }

    /** エラー詳細（行番号 + フィールド + メッセージ） */
    public record CsvImportError(
            int rowNumber,
            String field,
            String message
    ) {
    }
}
