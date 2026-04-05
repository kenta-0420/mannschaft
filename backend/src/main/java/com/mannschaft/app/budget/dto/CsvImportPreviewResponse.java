package com.mannschaft.app.budget.dto;

import java.util.List;

/**
 * CSV取込プレビューレスポンス。解析結果と一時保存キーを返す。
 */
public record CsvImportPreviewResponse(
        String previewKey,
        int totalRows,
        int validRows,
        int errorRows,
        List<CsvRowPreview> rows,
        List<String> errors
) {

    /**
     * CSVプレビュー行。
     */
    public record CsvRowPreview(
            int rowNumber,
            String categoryName,
            String transactionType,
            String amount,
            String transactionDate,
            String description,
            boolean valid,
            String errorMessage
    ) {
    }
}
