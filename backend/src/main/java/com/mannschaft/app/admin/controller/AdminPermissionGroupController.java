package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.PermissionGroupResponse;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import com.mannschaft.app.role.service.PermissionGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理者向け権限グループコントローラー。
 * 権限グループのCRUD・複製・メンバー割当/解除を管理する。
 */
@RestController
@RequestMapping("/api/v1/admin/permission-groups")
@Tag(name = "管理 - 権限グループ", description = "F10.1 権限グループ管理API")
@RequiredArgsConstructor
public class AdminPermissionGroupController {

    private final PermissionGroupService permissionGroupService;

    /**
     * 権限グループ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "権限グループ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> getPermissionGroups(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        List<PermissionGroupResponse> groups = permissionGroupService.getPermissionGroups(scopeId, scopeType);
        return ResponseEntity.ok(ApiResponse.of(groups));
    }

    /**
     * 権限グループを作成する。
     */
    @PostMapping
    @Operation(summary = "権限グループ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> createPermissionGroup(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @Valid @RequestBody PermissionGroupRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<PermissionGroupResponse> response =
                permissionGroupService.createPermissionGroup(scopeId, scopeType, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 権限グループを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "権限グループ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> updatePermissionGroup(
            @PathVariable Long id,
            @Valid @RequestBody PermissionGroupRequest request) {
        return ResponseEntity.ok(permissionGroupService.updatePermissionGroup(id, request));
    }

    /**
     * 権限グループを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "権限グループ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePermissionGroup(@PathVariable Long id) {
        permissionGroupService.deletePermissionGroup(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 権限グループを複製する。
     */
    @PostMapping("/{id}/duplicate")
    @Operation(summary = "権限グループ複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<PermissionGroupResponse>> duplicatePermissionGroup(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<PermissionGroupResponse> response = permissionGroupService.duplicatePermissionGroup(id, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 権限グループにメンバーを割り当てる。
     */
    @PatchMapping("/{id}/assign/{userId}")
    @Operation(summary = "権限グループメンバー割当")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "割当成功")
    public ResponseEntity<ApiResponse<Void>> assignMember(
            @PathVariable Long id,
            @PathVariable Long userId,
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        var request = new UserPermissionGroupAssignRequest(List.of(id));
        permissionGroupService.assignUserPermissionGroups(userId, scopeId, scopeType, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    /**
     * 権限グループからメンバーを解除する。
     */
    @PatchMapping("/{id}/unassign/{userId}")
    @Operation(summary = "権限グループメンバー解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "解除成功")
    public ResponseEntity<ApiResponse<Void>> unassignMember(
            @PathVariable Long id,
            @PathVariable Long userId,
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 空リストで割当を解除（既存の割当を全削除して空に）
        var request = new UserPermissionGroupAssignRequest(List.of());
        permissionGroupService.assignUserPermissionGroups(userId, scopeId, scopeType, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(null));
    }
}
