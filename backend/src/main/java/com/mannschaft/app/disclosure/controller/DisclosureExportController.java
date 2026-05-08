package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.dto.DisclosureExportRequest;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.service.DisclosureExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重要事項説明書 出力 / 履歴 コントローラ（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 出力 API に対応。組織スコープのみ提供。</p>
 *
 * <p>パス:
 * <ul>
 *   <li>POST {@code /api/v1/organizations/{id}/disclosure-drafts/{draftId}/export?format=pdf|xlsx}</li>
 *   <li>GET  {@code /api/v1/organizations/{id}/disclosure-exports}</li>
 *   <li>GET  {@code /api/v1/organizations/{id}/disclosure-exports/{exportId}}</li>
 *   <li>GET  {@code /api/v1/organizations/{id}/disclosure-exports/{exportId}/download}</li>
 * </ul>
 * </p>
 *
 * <p>FIXME: 権限制御は本フェーズでは認証ガードのみ。Phase 2-β-5 以降で
 * ADMIN / DEPUTY_ADMIN(DISCLOSURE_EXPORT) の判定を組込む。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
@RequiredArgsConstructor
public class DisclosureExportController {

    private final DisclosureExportService exportService;

    /**
     * ドラフトを出力する。{@code format} は {@code pdf} / {@code xlsx} を受け付ける（大文字小文字無視）。
     */
    @PostMapping("/disclosure-drafts/{draftId}/export")
    public ResponseEntity<ApiResponse<DisclosureExportResponse>> exportDraft(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("draftId") Long draftId,
            @RequestParam(value = "format", defaultValue = "pdf") String format,
            @Valid @RequestBody(required = false) DisclosureExportRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DisclosureOutputFormat outputFormat = parseFormat(format);
        DisclosureExportRequest body = request != null ? request : new DisclosureExportRequest(null, null);
        DisclosureExportResponse response = exportService.exportDraft(
                organizationId, draftId, outputFormat, userId,
                body.recipientNote(), body.allowPersonalInfoFlag());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/disclosure-exports")
    public PagedResponse<DisclosureExportResponse> listExports(
            @PathVariable("organizationId") Long organizationId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(page, size);
        Page<DisclosureExportResponse> result = exportService.listExports(organizationId, pageable);
        return PagedResponse.of(
                result.getContent(),
                new PagedResponse.PageMeta(
                        result.getTotalElements(),
                        result.getNumber(),
                        result.getSize(),
                        result.getTotalPages()));
    }

    @GetMapping("/disclosure-exports/{exportId}")
    public ApiResponse<DisclosureExportResponse> getExport(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("exportId") Long exportId) {
        SecurityUtils.getCurrentUserId();
        return ApiResponse.of(exportService.getExport(organizationId, exportId));
    }

    /**
     * presigned ダウンロード URL を発行する。改ざん検出により SHA-256 不一致時は
     * {@link DisclosureErrorCode#DISCLOSURE_010} を返す（GlobalExceptionHandler 経由で 503）。
     */
    @GetMapping("/disclosure-exports/{exportId}/download")
    public ApiResponse<DisclosureExportResponse> downloadExport(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("exportId") Long exportId) {
        SecurityUtils.getCurrentUserId();
        return ApiResponse.of(exportService.generateDownloadUrl(organizationId, exportId));
    }

    private DisclosureOutputFormat parseFormat(String format) {
        if (format == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        return switch (format.toLowerCase()) {
            case "pdf" -> DisclosureOutputFormat.PDF;
            case "xlsx", "excel" -> DisclosureOutputFormat.EXCEL;
            case "docx", "word" -> DisclosureOutputFormat.WORD; // Phase 3 以降
            default -> throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        };
    }
}
