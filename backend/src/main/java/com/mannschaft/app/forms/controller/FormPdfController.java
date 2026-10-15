package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.forms.dto.FormPdfDownloadUrlResponse;
import com.mannschaft.app.forms.dto.FormPdfGenerateResponse;
import com.mannschaft.app.forms.service.FormPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * フォーム提出 PDF コントローラ（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>PDF 生成と Pre-signed ダウンロード URL 発行を提供する。
 * 認可は Service 層で「提出者本人 / テンプレート作成者」を検証する。
 * ADMIN+ 経由のアクセスは別途 SecurityUtils / 認可フィルタで対応する。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-submissions/{submissionId}")
@Tag(name = "フォーム PDF", description = "F05.7 提出 PDF 生成・ダウンロード")
@RequiredArgsConstructor
public class FormPdfController {

    private final FormPdfService formPdfService;

    /**
     * 提出 PDF を生成する（同期）。
     */
    @PostMapping("/pdf")
    @Operation(summary = "PDF 生成", description = "提出済みフォームを Thymeleaf テンプレートで PDF 化し R2/S3 にアップロードする")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF 生成成功")
    public ResponseEntity<ApiResponse<FormPdfGenerateResponse>> generatePdf(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long submissionId) {
        FormPdfGenerateResponse response = formPdfService.generatePdf(
                scopeType, scopeId, submissionId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 生成済み PDF の Pre-signed ダウンロード URL を発行する（5 分有効）。
     */
    @GetMapping("/pdf/download-url")
    @Operation(summary = "PDF ダウンロード URL 取得", description = "5 分有効の Pre-signed URL を発行する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL 発行成功")
    public ResponseEntity<ApiResponse<FormPdfDownloadUrlResponse>> downloadUrl(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long submissionId) {
        FormPdfDownloadUrlResponse response = formPdfService.generateDownloadUrl(
                scopeType, scopeId, submissionId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
