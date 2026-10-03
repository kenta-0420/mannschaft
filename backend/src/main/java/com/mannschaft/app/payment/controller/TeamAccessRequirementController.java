package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.AccessRequirementsRequest;
import com.mannschaft.app.payment.dto.AccessRequirementsResponse;
import com.mannschaft.app.payment.service.AccessRequirementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チームアクセス要件コントローラー。チーム全体ロック設定の GET/PUT を提供する。
 * <p>
 * エンドポイント数: 2（GET, PUT）
 *
 * <p><b>認可根治戦役 Wave6（B3・2026-07-21）:</b> 双子の {@link OrganizationAccessRequirementController}
 * （Wave5早馬B1b で敷設済み）と同水準へ揃える。閲覧系（GET）は
 * {@link AccessControlService#checkMembership}、変更系（PUT）は
 * {@link AccessControlService#checkAdminOrAbove} を "TEAM" スコープで要求する。
 * 変更前の状態に関する詳細はマージ後に戦役台帳へ記録する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{id}/access-requirements")
@Tag(name = "チームアクセス要件", description = "F08.2 チーム全体ロック設定")
@RequiredArgsConstructor
public class TeamAccessRequirementController {

    private final AccessRequirementService accessRequirementService;
    private final AccessControlService accessControlService;

    /**
     * チーム全体ロック設定を取得する。
     */
    @GetMapping
    @Operation(summary = "チームアクセス要件取得")
    public ResponseEntity<ApiResponse<AccessRequirementsResponse>> getAccessRequirements(
            @PathVariable Long id) {
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), id, "TEAM");
        AccessRequirementsResponse response = accessRequirementService.getTeamAccessRequirements(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム全体ロック設定を一括設定する。
     */
    @PutMapping
    @Operation(summary = "チームアクセス要件設定")
    public ResponseEntity<ApiResponse<AccessRequirementsResponse>> setAccessRequirements(
            @PathVariable Long id,
            @Valid @RequestBody AccessRequirementsRequest request) {
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), id, "TEAM");
        AccessRequirementsResponse response = accessRequirementService.setTeamAccessRequirements(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
