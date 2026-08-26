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
 * 組織アクセス要件コントローラー。組織全体ロック設定の GET/PUT を提供する。
 * <p>
 * エンドポイント数: 2（GET, PUT）
 *
 * <p><b>認可根治戦役 Wave5早馬（B1b・2026-07-17）:</b> 兄弟 {@link OrganizationPaymentController}
 * と同型で、全 EP に {@link AccessControlService} を適用する。
 * 閲覧系（GET）は {@link AccessControlService#checkMembership}、
 * 変更系（PUT）は {@link AccessControlService#checkAdminOrAbove} を要求する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/access-requirements")
@Tag(name = "組織アクセス要件", description = "F08.2 組織全体ロック設定")
@RequiredArgsConstructor
public class OrganizationAccessRequirementController {

    private final AccessRequirementService accessRequirementService;
    private final AccessControlService accessControlService;

    @GetMapping
    @Operation(summary = "組織アクセス要件取得")
    public ResponseEntity<ApiResponse<AccessRequirementsResponse>> getAccessRequirements(
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(userId, id, "ORGANIZATION");
        AccessRequirementsResponse response = accessRequirementService.getOrganizationAccessRequirements(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PutMapping
    @Operation(summary = "組織アクセス要件設定")
    public ResponseEntity<ApiResponse<AccessRequirementsResponse>> setAccessRequirements(
            @PathVariable Long id,
            @Valid @RequestBody AccessRequirementsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, id, "ORGANIZATION");
        AccessRequirementsResponse response = accessRequirementService.setOrganizationAccessRequirements(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
