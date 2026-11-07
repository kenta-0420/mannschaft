package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.role.dto.PermissionCatalogItemResponse;
import com.mannschaft.app.role.service.PermissionCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 権限カタログ（F01.2 02_api_design.md の {@code GET /api/v1/permissions}・ADMIN+）。
 *
 * <p>権限グループの作成・更新で選べる権限の一覧。認可は、問い合わせるスコープ（チーム/組織）の
 * ADMIN+ であること。スコープ種別は検証したうえで、そのスコープに対して確認する。</p>
 */
@RestController
@RequestMapping("/api/v1/permissions")
@Tag(name = "権限カタログ")
@RequiredArgsConstructor
public class PermissionCatalogController {

    private final PermissionCatalogService permissionCatalogService;
    private final AccessControlService accessControlService;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "権限グループの管理に必要な権限カタログは中核の権限管理機能として常時提供する")
    @Operation(summary = "権限カタログ一覧（スコープの ADMIN+）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功（名前順）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "scopeType が TEAM / ORGANIZATION でない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "指定スコープの ADMIN+ でない")
    public ResponseEntity<ApiResponse<List<PermissionCatalogItemResponse>>> list(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        String validated = PermissionCatalogService.requireGroupScope(scopeType).name();
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), scopeId, validated);
        return ResponseEntity.ok(ApiResponse.of(permissionCatalogService.list(validated)));
    }
}
