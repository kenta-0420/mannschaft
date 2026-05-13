package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.GenerateHandoverPackRequest;
import com.mannschaft.app.repairplan.dto.HandoverPackDownloadResponse;
import com.mannschaft.app.repairplan.dto.HandoverPackDto;
import com.mannschaft.app.repairplan.module.RequireRepairPlanModule;
import com.mannschaft.app.repairplan.service.BoardHandoverPackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 申し送りパック コントローラ（F08.8 Phase 5）。
 *
 * <p>URL 形式: {@code /api/v1/{scopeType}/{scopeId}/repair-plan/handover-packs}</p>
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>POST   / — パック生成（ADMIN/DEPUTY_ADMIN 以上）</li>
 *   <li>GET    / — パック一覧（メンバーシップ必須）</li>
 *   <li>GET    /{packId}/download — ダウンロード URL 取得（メンバーシップ必須）</li>
 *   <li>DELETE /{packId} — パック削除（ADMIN 以上）</li>
 * </ul>
 */
@RequireRepairPlanModule
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/repair-plan/handover-packs")
@Tag(name = "申し送りパック", description = "F08.8 Phase 5 — 理事申し送り PDF パック管理")
@RequiredArgsConstructor
public class BoardHandoverPackController {

    private final BoardHandoverPackService service;

    /**
     * 申し送りパックを生成する（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @PostMapping
    @Operation(summary = "申し送りパック生成（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<HandoverPackDto>> generatePack(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId,
            @Valid @RequestBody GenerateHandoverPackRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        HandoverPackDto dto = service.generatePack(
                normalizeScope(scopeType), scopeId, organizationId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * 申し送りパック一覧を取得する（メンバーシップ必須）。
     */
    @GetMapping
    @Operation(summary = "申し送りパック一覧（メンバーシップ必須）")
    public ResponseEntity<ApiResponse<List<HandoverPackDto>>> listPacks(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        List<HandoverPackDto> result = service.listPacks(
                normalizeScope(scopeType), scopeId, organizationId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 申し送りパックの署名付きダウンロード URL を取得する（メンバーシップ必須）。
     */
    @GetMapping("/{packId}/download")
    @Operation(summary = "ダウンロード URL 取得（メンバーシップ必須）")
    public ResponseEntity<ApiResponse<HandoverPackDownloadResponse>> getDownloadUrl(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID packId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        HandoverPackDownloadResponse response = service.getDownloadUrl(packId, organizationId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 申し送りパックを削除する（ADMIN 以上）。
     */
    @DeleteMapping("/{packId}")
    @Operation(summary = "申し送りパック削除（ADMIN）")
    public ResponseEntity<Void> deletePack(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable UUID packId,
            @RequestHeader("X-Organization-Id") Long organizationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.deletePack(packId, organizationId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * URL の {@code {scopeType}} を正規化する。
     */
    private static String normalizeScope(String raw) {
        if (raw == null) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.equals("TEAM") || upper.equals("TEAMS")) {
            return "TEAM";
        }
        if (upper.equals("ORGANIZATION") || upper.equals("ORGANIZATIONS")
                || upper.equals("ORG") || upper.equals("ORGS")) {
            return "ORGANIZATION";
        }
        throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
    }
}
