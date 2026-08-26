package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartRequest;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartResponse;
import com.mannschaft.app.disclosure.dto.DisclosureExportRequest;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.dto.ExtendExpiryRequest;
import com.mannschaft.app.disclosure.service.DisclosureCirculationService;
import com.mannschaft.app.disclosure.service.DisclosureExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * <p><strong>権限制御</strong>（認可根治戦役 Wave3-B4 で実装）: 閲覧系（一覧・詳細）は
 * {@code AccessControlService.checkMembership}、出力実行・ダウンロード・期限延長は
 * {@code checkAdminOrAbove} を {@link DisclosureExportService} 側で検証する。
 * DEPUTY_ADMIN の Permission 単位（DISCLOSURE_EXPORT/DISCLOSURE_VIEW）細分化は
 * 引き続き Phase 2-β-5 以降の課題として残す。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
@RequiredArgsConstructor
public class DisclosureExportController {

    private final DisclosureExportService exportService;
    private final DisclosureCirculationService circulationService;

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
        Long userId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(page, size);
        Page<DisclosureExportResponse> result = exportService.listExports(organizationId, userId, pageable);
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
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(exportService.getExport(organizationId, userId, exportId));
    }

    /**
     * presigned ダウンロード URL を発行する。改ざん検出により SHA-256 不一致時は
     * {@link DisclosureErrorCode#DISCLOSURE_010} を返す（GlobalExceptionHandler 経由で 503）。
     */
    @GetMapping("/disclosure-exports/{exportId}/download")
    public ApiResponse<DisclosureExportResponse> downloadExport(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("exportId") Long exportId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(exportService.generateDownloadUrl(organizationId, userId, exportId));
    }

    /**
     * 出力履歴の自動削除予定日（{@code expires_at}）を延長する（F09.14 Phase 3-E、設計書 §5.7）。
     *
     * <p>本日から最大 7 年まで延長可能。過去日時は {@link DisclosureErrorCode#DISCLOSURE_011}
     * (422) を返す。</p>
     *
     * <p><strong>権限制御</strong>: ADMIN のみ許可する（設計書 §5.7、認可根治戦役 Wave3-B4 で
     * {@code AccessControlService.checkAdminOrAbove} を実装済み）。</p>
     */
    @PatchMapping("/disclosure-exports/{exportId}/extend-expiry")
    public ApiResponse<DisclosureExportResponse> extendExpiry(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("exportId") Long exportId,
            @Valid @RequestBody ExtendExpiryRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DisclosureExportResponse response = exportService.extendExpiry(
                organizationId, userId, exportId, request.newExpiresAt());
        return ApiResponse.of(response);
    }

    /**
     * 出力履歴に対する電子印鑑承認回覧を開始する（F09.14 Phase 3-D）。
     *
     * <p>設計書 §4 / §5.6 に対応。手動クリック方式（自動開始ではない）。
     * F05.2 {@code CirculationDocumentEntity} を作成し、{@code disclosure_exports.circulation_document_id}
     * へ保存する。</p>
     */
    @PostMapping("/disclosure-exports/{exportId}/circulation")
    public ResponseEntity<ApiResponse<DisclosureCirculationStartResponse>> startCirculation(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("exportId") Long exportId,
            @Valid @RequestBody DisclosureCirculationStartRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DisclosureCirculationStartResponse response = circulationService.startCirculation(
                organizationId, exportId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
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
