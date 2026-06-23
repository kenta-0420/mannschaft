package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.template.dto.OrgModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.service.ModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 組織モジュール管理コントローラー。組織単位のモジュール有効化・一覧取得を提供する。
 * GET: MEMBER以上、PATCH: ADMINのみ許可する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}/modules")
@Tag(name = "組織モジュール管理")
@RequiredArgsConstructor
public class OrganizationModuleController {

    private final ModuleService moduleService;
    private final AccessControlService accessControlService;
    private final OrganizationService organizationService;

    /**
     * 組織の有効モジュール一覧を取得する。
     * MEMBER以上のユーザーが参照できる（SUPPORTER/GUEST/未加入は403）。
     *
     * @param slug 組織スラッグ（URL識別子）
     * @return 組織モジュールレスポンスリスト
     */
    @GetMapping
    @Operation(summary = "組織モジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<OrgModuleResponse>>> getOrganizationModules(
            @PathVariable String slug) {
        Long orgId = organizationService.resolveOrgId(slug);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // MEMBER以上であることを確認（SUPPORTER/GUESTは isMember=false のため 403）
        accessControlService.checkMembership(currentUserId, orgId, "ORGANIZATION");
        return ResponseEntity.ok(ApiResponse.of(moduleService.getOrganizationModules(orgId)));
    }

    /**
     * 組織のモジュール有効/無効を切り替える。
     * ADMINのみ実行可能（DEPUTY_ADMIN以下は403）。
     *
     * @param slug     組織スラッグ（URL識別子）
     * @param moduleId モジュールID（パスパラメーター、リクエストボディと一致させること）
     * @param request  トグルリクエスト
     * @return 200 OK
     */
    @PatchMapping("/{moduleId}/toggle")
    @Operation(summary = "組織モジュール有効/無効切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<Void> toggleOrganizationModule(
            @PathVariable String slug,
            @PathVariable Long moduleId,
            @Valid @RequestBody ToggleModuleRequest request) {
        Long orgId = organizationService.resolveOrgId(slug);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // ADMINのみ許可
        if (!accessControlService.isAdmin(currentUserId, orgId, "ORGANIZATION")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        moduleService.toggleOrganizationModule(orgId, request, currentUserId);
        return ResponseEntity.ok().build();
    }
}
