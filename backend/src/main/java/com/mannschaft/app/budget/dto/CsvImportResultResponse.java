package com.mannschaft.app.budget.dto;

/**
 * CSV取込確定結果レスポンス。
 */
public record CsvImportResultResponse(
        int totalRows,
        int insertedRows,
        int skippedRows
) {
}
