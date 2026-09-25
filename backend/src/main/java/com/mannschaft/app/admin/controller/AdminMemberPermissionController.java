package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.role.dto.MemberPermissionUpdateRequest;
import com.mannschaft.app.role.dto.MemberPermissionsResponse;
import com.mannschaft.app.role.service.MemberDefaultPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** スコープ別 MEMBER 既定権限管理 API。 */
@RestController
@RequestMapping("/api/v1/admin/member-permissions")
@Tag(name = "管理 - MEMBER既定権限", description = "F10.1 MEMBER既定権限管理API")
@RequiredArgsConstructor
public class AdminMemberPermissionController {

    private final MemberDefaultPermissionService memberDefaultPermissionService;

    /** MEMBER 既定3権限を取得する。 */
    @GetMapping
    @AuthorizedInService
    @Operation(summary = "MEMBER既定権限取得")
    public ResponseEntity<ApiResponse<MemberPermissionsResponse>> get(
            @RequestParam String scopeType, @RequestParam Long scopeId) {
        return ResponseEntity.ok(ApiResponse.of(memberDefaultPermissionService.get(
                scopeType, scopeId, SecurityUtils.getCurrentUserId())));
    }

    /** MEMBER 既定3権限を完全置換する。 */
    @PutMapping
    @AuthorizedInService
    @Operation(summary = "MEMBER既定権限更新")
    public ResponseEntity<ApiResponse<MemberPermissionsResponse>> update(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody MemberPermissionUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                memberDefaultPermissionService.update(
                        scopeType, scopeId, SecurityUtils.getCurrentUserId(), request)));
    }
}
