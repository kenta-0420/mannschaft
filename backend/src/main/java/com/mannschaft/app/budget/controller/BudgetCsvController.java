package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.CsvImportConfirmRequest;
import com.mannschaft.app.budget.dto.CsvImportPreviewResponse;
import com.mannschaft.app.budget.dto.CsvImportResultResponse;
import com.mannschaft.app.budget.service.BudgetCsvService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 予算CSVコントローラー。
 * 予算データのCSVエクスポート・インポートAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/budget/fiscal-years/{fiscalYearId}")
@RequiredArgsConstructor
public class BudgetCsvController {

    private final BudgetCsvService budgetCsvService;

    /**
     * 予算データをCSV形式でエクスポートする。
     *
     * @param fiscalYearId 会計年度ID
     * @return CSVバイトデータ
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public byte[] export(@PathVariable Long fiscalYearId) {
        return budgetCsvService.export(fiscalYearId);
    }

    /**
     * CSVファイルをアップロードしてインポートプレビューを取得する。
     *
     * @param fiscalYearId 会計年度ID
     * @param file CSVファイル
     * @return インポートプレビュー
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CsvImportPreviewResponse> importPreview(
            @PathVariable Long fiscalYearId,
            @RequestParam("file") MultipartFile file) throws IOException {
        String csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        return ApiResponse.of(budgetCsvService.importPreview(fiscalYearId, csvContent));
    }

    /**
     * プレビュー済みのCSVインポートを確定する。
     *
     * @param fiscalYearId 会計年度ID
     * @param request 確定リクエスト
     * @return インポート結果
     */
    @PostMapping("/import/confirm")
    public ApiResponse<CsvImportResultResponse> importConfirm(
            @PathVariable Long fiscalYearId,
            @Valid @RequestBody CsvImportConfirmRequest request) {
        return ApiResponse.of(budgetCsvService.importConfirm(request));
    }
}
