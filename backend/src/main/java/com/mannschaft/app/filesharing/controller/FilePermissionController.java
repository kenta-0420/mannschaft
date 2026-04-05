package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.CreatePermissionRequest;
import com.mannschaft.app.filesharing.dto.PermissionResponse;
import com.mannschaft.app.filesharing.service.FilePermissionService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ファイル権限コントローラー。ファイル・フォルダの権限管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/file-permissions")
@Tag(name = "ファイル共有 - 権限", description = "F05.5 ファイル権限管理")
@RequiredArgsConstructor
public class FilePermissionController {

    private final FilePermissionService permissionService;

    /**
     * 対象の権限一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "権限一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PermissionResponse>>> listPermissions(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        List<PermissionResponse> response = permissionService.listPermissions(targetType, targetId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 権限を付与する。
     */
    @PostMapping
    @Operation(summary = "権限付与")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PermissionResponse>> createPermission(
            @Valid @RequestBody CreatePermissionRequest request) {
        PermissionResponse response = permissionService.createPermission(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 権限を削除する。
     */
    @DeleteMapping("/{permissionId}")
    @Operation(summary = "権限削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePermission(
            @PathVariable Long permissionId) {
        permissionService.deletePermission(permissionId);
        return ResponseEntity.noContent().build();
    }
}
