package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.dto.UpdateFolderRequest;
import com.mannschaft.app.filesharing.service.SharedFolderService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チームフォルダコントローラー。チームスコープのフォルダCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/folders")
@Tag(name = "ファイル共有 - チームフォルダ", description = "F05.5 チームフォルダ管理")
@RequiredArgsConstructor
public class TeamFolderController {

    private final SharedFolderService folderService;


    /**
     * チームのルートフォルダ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チームルートフォルダ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FolderResponse>>> listRootFolders(
            @PathVariable Long teamId) {
        List<FolderResponse> response = folderService.listTeamRootFolders(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 子フォルダ一覧を取得する。
     */
    @GetMapping("/{folderId}/children")
    @Operation(summary = "子フォルダ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FolderResponse>>> listChildFolders(
            @PathVariable Long teamId,
            @PathVariable Long folderId) {
        List<FolderResponse> response = folderService.listChildFolders(folderId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダ詳細を取得する。
     */
    @GetMapping("/{folderId}")
    @Operation(summary = "フォルダ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FolderResponse>> getFolder(
            @PathVariable Long teamId,
            @PathVariable Long folderId) {
        FolderResponse response = folderService.getFolder(folderId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チームフォルダを作成する。
     */
    @PostMapping
    @Operation(summary = "チームフォルダ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FolderResponse>> createFolder(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateFolderRequest request) {
        FolderResponse response = folderService.createTeamFolder(teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * フォルダを更新する。
     */
    @PatchMapping("/{folderId}")
    @Operation(summary = "フォルダ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FolderResponse>> updateFolder(
            @PathVariable Long teamId,
            @PathVariable Long folderId,
            @Valid @RequestBody UpdateFolderRequest request) {
        FolderResponse response = folderService.updateFolder(folderId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダを削除する。
     */
    @DeleteMapping("/{folderId}")
    @Operation(summary = "フォルダ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable Long teamId,
            @PathVariable Long folderId) {
        folderService.deleteFolder(folderId);
        return ResponseEntity.noContent().build();
    }
}
