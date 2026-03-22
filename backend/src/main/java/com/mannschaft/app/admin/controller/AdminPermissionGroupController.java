package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.PermissionGroupResponse;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 権限グループ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "権限グループ一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PermissionGroupResponse>>> getPermissionGroups(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of(List.of()));
    }

    /**
     * 権限グループを取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "権限グループ取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<String>> getPermissionGroup(@PathVariable Long id) {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: 権限グループ詳細を返す"));
    }

    /**
     * 権限グループを作成する。
     */
    @PostMapping
    @Operation(summary = "権限グループ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<String>> createPermissionGroup() {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of("TODO: 権限グループ作成を実行"));
    }

    /**
     * 権限グループを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "権限グループ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<String>> updatePermissionGroup(@PathVariable Long id) {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: 権限グループ更新を実行"));
    }

    /**
     * 権限グループを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "権限グループ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePermissionGroup(@PathVariable Long id) {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.noContent().build();
    }

    /**
     * 権限グループを複製する。
     */
    @PostMapping("/{id}/duplicate")
    @Operation(summary = "権限グループ複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<String>> duplicatePermissionGroup(@PathVariable Long id) {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of("TODO: 権限グループ複製を実行"));
    }

    /**
     * 権限グループにメンバーを割り当てる。
     */
    @PatchMapping("/{id}/assign/{userId}")
    @Operation(summary = "権限グループメンバー割当")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "割当成功")
    public ResponseEntity<ApiResponse<String>> assignMember(
            @PathVariable Long id,
            @PathVariable Long userId) {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: メンバー割当を実行"));
    }

    /**
     * 権限グループからメンバーを解除する。
     */
    @PatchMapping("/{id}/unassign/{userId}")
    @Operation(summary = "権限グループメンバー解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "解除成功")
    public ResponseEntity<ApiResponse<String>> unassignMember(
            @PathVariable Long id,
            @PathVariable Long userId) {
        // TODO: 権限グループ機能のサービス実装後に連携
        return ResponseEntity.ok(ApiResponse.of("TODO: メンバー解除を実行"));
    }
}
