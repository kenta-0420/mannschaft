package com.mannschaft.app.repairplan.dto;

/**
 * 修繕計画項目 CSV インポート確定レスポンス。
 */
public record CsvImportConfirmResponse(
        int totalRows,
        int insertedRows,
        int skippedRows
) {
}
