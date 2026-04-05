package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.service.SharedFolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織フォルダコントローラー。組織スコープのフォルダCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/folders")
@Tag(name = "ファイル共有 - 組織フォルダ", description = "F05.5 組織フォルダ管理")
@RequiredArgsConstructor
public class OrgFolderController {

    private final SharedFolderService folderService;


    /**
     * 組織のルートフォルダ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "組織ルートフォルダ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FolderResponse>>> listRootFolders(
            @PathVariable Long organizationId) {
        List<FolderResponse> response = folderService.listOrgRootFolders(organizationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織フォルダを作成する。
     */
    @PostMapping
    @Operation(summary = "組織フォルダ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FolderResponse>> createFolder(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateFolderRequest request) {
        FolderResponse response = folderService.createOrgFolder(organizationId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
