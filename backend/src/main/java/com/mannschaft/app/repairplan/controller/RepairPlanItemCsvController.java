package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.dto.CsvImportConfirmRequest;
import com.mannschaft.app.repairplan.module.RequireRepairPlanModule;
import com.mannschaft.app.repairplan.dto.CsvImportConfirmResponse;
import com.mannschaft.app.repairplan.dto.CsvImportPreviewResponse;
import com.mannschaft.app.repairplan.service.RepairPlanItemCsvService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 修繕計画項目 CSV インポート用コントローラ（F08.8 Phase 1）。
 *
 * <p>足軽2 が作成する {@code RepairPlanItemController}（CRUD 系）と分離してファイル衝突を避ける。
 * 同じパス階層下に CSV インポート専用エンドポイント 2 本を提供する。</p>
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/repair-plan/items")
@RequiredArgsConstructor
public class RepairPlanItemCsvController {

    private final RepairPlanItemCsvService csvService;

    /**
     * CSV ファイルをアップロードしてプレビューを返す（dry_run）。
     * Valkey に CSV 原文を 30 分保存し、{@code import_token} を発行する。
     */
    @PostMapping(value = "/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CsvImportPreviewResponse> preview(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "organization_id", required = false) Long organizationIdParam) {

        Long userId = SecurityUtils.getCurrentUserId();
        Long organizationId = resolveOrganizationId(scopeType, scopeId, organizationIdParam);
        CsvImportPreviewResponse response = csvService.preview(file, userId, scopeId, scopeType, organizationId);
        return ApiResponse.of(response);
    }

    /**
     * プレビュー時に発行された {@code import_token} を使ってインポートを確定する。
     */
    @PostMapping("/import-csv/confirm")
    public ApiResponse<CsvImportConfirmResponse> confirm(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(value = "organization_id", required = false) Long organizationIdParam,
            @Valid @RequestBody CsvImportConfirmRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        Long organizationId = resolveOrganizationId(scopeType, scopeId, organizationIdParam);
        CsvImportConfirmResponse response = csvService.confirm(
                request.previewKey(), userId, scopeId, scopeType, organizationId);
        return ApiResponse.of(response);
    }

    /**
     * 組織 ID を解決する。
     *
     * <ul>
     *   <li>{@code scopeType = ORGANIZATION} ⇒ {@code scopeId} を組織 ID として扱う</li>
     *   <li>{@code scopeType = TEAM}         ⇒ クライアントから {@code organization_id} クエリパラメータで指定する</li>
     * </ul>
     *
     * <p>足軽2 で別途 Team→Organization 解決ロジックが実装された後、{@code TeamService}
     * 経由のリゾルバに差し替える予定（TODO: F08.8 Phase 2）。</p>
     */
    private Long resolveOrganizationId(String scopeType, Long scopeId, Long organizationIdParam) {
        if ("ORGANIZATION".equals(scopeType)) {
            return scopeId;
        }
        return organizationIdParam;
    }
}
