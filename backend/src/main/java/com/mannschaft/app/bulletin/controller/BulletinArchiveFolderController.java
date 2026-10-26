package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ArchiveFolderResponse;
import com.mannschaft.app.bulletin.dto.ArchiveFolderTreeResponse;
import com.mannschaft.app.bulletin.dto.CreateArchiveFolderRequest;
import com.mannschaft.app.bulletin.dto.DeleteArchiveFolderResponse;
import com.mannschaft.app.bulletin.dto.MoveThreadFolderRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateArchiveFolderRequest;
import com.mannschaft.app.bulletin.service.BulletinArchiveFolderService;
import com.mannschaft.app.bulletin.service.BulletinScopeIdResolver;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import java.util.UUID;

/**
 * 掲示板 保管庫フォルダコントローラー（設計書 F05.1 §4）。
 *
 * <p>フォルダのツリー取得・作成・更新（移動）・削除（退避）、保管庫スレッド一覧、
 * スレッドのフォルダ振り分けの 6 API を提供する。アーカイブ拡張（POST .../threads/{id}/archive）は
 * {@link BulletinThreadController} 側で対応する。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/bulletin/archive")
@Tag(name = "掲示板 保管庫フォルダ", description = "F05.1 保管庫（アーカイブ）フォルダ CRUD・スレッド振り分け")
@RequiredArgsConstructor
public class BulletinArchiveFolderController {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final BulletinArchiveFolderService folderService;
    private final BulletinThreadService threadService;
    private final BulletinScopeIdResolver scopeIdResolver;

    /**
     * 保管庫フォルダ一覧をツリー構造で取得する。
     */
    @GetMapping("/folders")
    @Operation(summary = "保管庫フォルダ一覧（ツリー）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ArchiveFolderTreeResponse> getFolderTree(
            @PathVariable String scopeType,
            @PathVariable String scopeId) {
        ScopeType type = ScopeType.fromPathSegment(scopeType);
        Long resolvedScopeId = scopeIdResolver.resolve(type, scopeId);
        ArchiveFolderTreeResponse response =
                folderService.getFolderTree(type, resolvedScopeId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * 保管庫フォルダを作成する。
     */
    @PostMapping("/folders")
    @Operation(summary = "保管庫フォルダ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ArchiveFolderResponse>> createFolder(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @Valid @RequestBody CreateArchiveFolderRequest request) {
        ScopeType type = ScopeType.fromPathSegment(scopeType);
        Long resolvedScopeId = scopeIdResolver.resolve(type, scopeId);
        ArchiveFolderResponse response =
                folderService.createFolder(type, resolvedScopeId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 保管庫フォルダを更新・移動する。
     */
    @PutMapping("/folders/{folderId}")
    @Operation(summary = "保管庫フォルダ更新・移動")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ArchiveFolderResponse>> updateFolder(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable UUID folderId,
            @Valid @RequestBody UpdateArchiveFolderRequest request) {
        ScopeType type = ScopeType.fromPathSegment(scopeType);
        Long resolvedScopeId = scopeIdResolver.resolve(type, scopeId);
        ArchiveFolderResponse response =
                folderService.updateFolder(type, resolvedScopeId, SecurityUtils.getCurrentUserId(), folderId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 保管庫フォルダを削除する（論理削除・配下退避）。
     */
    @DeleteMapping("/folders/{folderId}")
    @Operation(summary = "保管庫フォルダ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<DeleteArchiveFolderResponse>> deleteFolder(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable UUID folderId) {
        ScopeType type = ScopeType.fromPathSegment(scopeType);
        Long resolvedScopeId = scopeIdResolver.resolve(type, scopeId);
        DeleteArchiveFolderResponse response =
                folderService.deleteFolder(type, resolvedScopeId, SecurityUtils.getCurrentUserId(), folderId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 保管庫内のアーカイブ済みスレッド一覧を取得する。
     *
     * <p>{@code folder_id} 省略 = 保管庫直下（未分類）、{@code folder_id=all} = 全保管庫、
     * それ以外は UUID として解釈してフォルダ絞り込み。不正な値（UUID でも all でもない）は 400。</p>
     */
    @GetMapping("/threads")
    @Operation(summary = "保管庫スレッド一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ThreadResponse>> listArchiveThreads(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @RequestParam(name = "folder_id", required = false) String folderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ScopeType type = ScopeType.fromPathSegment(scopeType);

        boolean allFolders = false;
        UUID folderUuid = null;
        if (folderId != null && !folderId.isBlank()) {
            if ("all".equalsIgnoreCase(folderId)) {
                allFolders = true;
            } else {
                try {
                    folderUuid = UUID.fromString(folderId);
                } catch (IllegalArgumentException e) {
                    // UUID でも all でもない不正値は 400
                    throw new com.mannschaft.app.common.BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_001);
                }
            }
        }

        Long resolvedScopeId = scopeIdResolver.resolve(type, scopeId);
        int pageSize = Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE);
        Page<ThreadResponse> result = threadService.listArchiveThreads(
                type, resolvedScopeId, SecurityUtils.getCurrentUserId(),
                folderUuid, allFolders, PageRequest.of(Math.max(page, 0), pageSize));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * アーカイブ済みスレッドを別の保管庫フォルダへ振り分ける。
     */
    @PatchMapping("/threads/{threadId}/folder")
    @Operation(summary = "保管庫スレッドのフォルダ振り分け")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "振り分け成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> moveThreadToFolder(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long threadId,
            @RequestBody(required = false) MoveThreadFolderRequest request) {
        ScopeType type = ScopeType.fromPathSegment(scopeType);
        Long resolvedScopeId = scopeIdResolver.resolve(type, scopeId);
        UUID archiveFolderId = request == null ? null : request.getArchiveFolderId();
        ThreadResponse response = threadService.moveThreadToFolder(
                type, resolvedScopeId, threadId, SecurityUtils.getCurrentUserId(), archiveFolderId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
