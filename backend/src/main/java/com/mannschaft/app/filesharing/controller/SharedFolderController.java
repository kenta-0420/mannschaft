package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderDetailResponse;
import com.mannschaft.app.filesharing.service.SharedFolderQueryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F05.5 共有フォルダコントローラー（{@code /api/v1/files/folders}）。
 *
 * <p>フロントエンド（{@code FileBrowser.vue}）が叩くフォルダ詳細・一覧・作成の 3 エンドポイントを提供する。
 * これらは従来コントローラが存在せず 500 になっていた（チーム／組織のファイル画面が機能しなかった）。</p>
 *
 * <p>認可は {@link SharedFolderQueryService} が folderId / scope からスコープを解決して自前で当てる
 * （既存 {@code SharedFolderService#getFolder} の認可素通り問題を流用しない）。未認証は
 * {@link SecurityUtils#getCurrentUserId()} が COMMON_000（401）を投げる。</p>
 */
@RestController
@RequestMapping("/api/v1/files/folders")
@Tag(name = "ファイル共有 - フォルダ", description = "F05.5 フォルダ詳細・一覧・作成")
@RequiredArgsConstructor
public class SharedFolderController {

    private final SharedFolderQueryService folderQueryService;

    /**
     * フォルダ詳細（サブフォルダ・ファイル・パンくず込み）を取得する。
     */
    @GetMapping("/{folderId}")
    @Operation(summary = "フォルダ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FolderDetailResponse>> getFolderDetail(
            @PathVariable Long folderId) {
        FolderDetailResponse response =
                folderQueryService.getFolderDetail(folderId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スコープのフォルダ一覧（ルート or サブ）を取得する。
     */
    @GetMapping
    @Operation(summary = "フォルダ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FolderDetailResponse.FolderSummary>>> listFolders(
            @RequestParam(name = "scope_type") String scopeType,
            @RequestParam(name = "scope_id") String scopeId,
            @RequestParam(name = "parent_id", required = false) Long parentId) {
        List<FolderDetailResponse.FolderSummary> response =
                folderQueryService.listFolders(scopeType, scopeId, parentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダを作成する。
     */
    @PostMapping
    @Operation(summary = "フォルダ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FolderDetailResponse.FolderSummary>> createFolder(
            @Valid @RequestBody CreateFolderRequest request) {
        FolderDetailResponse.FolderSummary response =
                folderQueryService.createFolder(request, request.getScopeId(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
