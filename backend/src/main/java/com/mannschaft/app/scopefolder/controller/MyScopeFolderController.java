package com.mannschaft.app.scopefolder.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.scopefolder.dto.AddFolderItemRequest;
import com.mannschaft.app.scopefolder.dto.CreateFolderRequest;
import com.mannschaft.app.scopefolder.dto.ReorderFoldersRequest;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.dto.UpdateFolderRequest;
import com.mannschaft.app.scopefolder.entity.ScopeType;
import com.mannschaft.app.scopefolder.service.MyScopeFolderService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * マイスコープフォルダコントローラー。
 * チームまたは組織のカスタムフォルダのCRUD・アイテム管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/me/scope-folders")
@RequiredArgsConstructor
@Tag(name = "マイスコープフォルダ")
public class MyScopeFolderController {

    private final MyScopeFolderService folderService;

    /**
     * フォルダ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "フォルダ一覧取得",
            description = "指定スコープタイプ（TEAM/ORGANIZATION）のフォルダ一覧をアイテムID込みで取得する")
    public ResponseEntity<List<ScopeFolderResponse>> getFolders(
            @RequestParam ScopeType scopeType) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ScopeFolderResponse> response = folderService.getFolders(userId, scopeType);
        return ResponseEntity.ok(response);
    }

    /**
     * フォルダを作成する。
     */
    @PostMapping
    @Operation(summary = "フォルダ作成",
            description = "新しいスコープフォルダを作成する（1スコープタイプあたり上限20件）")
    public ResponseEntity<ScopeFolderResponse> createFolder(
            @RequestParam ScopeType scopeType,
            @Valid @RequestBody CreateFolderRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeFolderResponse response = folderService.createFolder(userId, scopeType, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * フォルダの並び順を変更する。
     * 固定パスのため /{folderId} より先に定義する。
     */
    @PutMapping("/reorder")
    @Operation(summary = "フォルダ並び替え",
            description = "指定スコープタイプのフォルダを orderedIds の順に並び替える")
    public ResponseEntity<Void> reorderFolders(
            @RequestParam ScopeType scopeType,
            @Valid @RequestBody ReorderFoldersRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        folderService.reorderFolders(userId, scopeType, req);
        return ResponseEntity.noContent().build();
    }

    /**
     * フォルダを更新する。
     */
    @PutMapping("/{folderId}")
    @Operation(summary = "フォルダ更新", description = "フォルダの名前・色を更新する")
    public ResponseEntity<ScopeFolderResponse> updateFolder(
            @PathVariable Long folderId,
            @Valid @RequestBody UpdateFolderRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeFolderResponse response = folderService.updateFolder(userId, folderId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * フォルダを削除する（ソフト削除）。
     */
    @DeleteMapping("/{folderId}")
    @Operation(summary = "フォルダ削除",
            description = "フォルダを論理削除する。アイテムはDBのCASCADEでハード削除される")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable Long folderId) {
        Long userId = SecurityUtils.getCurrentUserId();
        folderService.deleteFolder(userId, folderId);
        return ResponseEntity.noContent().build();
    }

    /**
     * フォルダにアイテムを追加する。
     */
    @PostMapping("/{folderId}/items")
    @Operation(summary = "アイテム追加",
            description = "フォルダにチーム/組織を追加する。既に別フォルダに属している場合は移動する（1アイテム1フォルダ制約）")
    public ResponseEntity<ScopeFolderResponse> addItem(
            @PathVariable Long folderId,
            @Valid @RequestBody AddFolderItemRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeFolderResponse response = folderService.addItem(userId, folderId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * フォルダからアイテムを削除する。
     */
    @DeleteMapping("/{folderId}/items/{scopeId}")
    @Operation(summary = "アイテム削除", description = "フォルダからチーム/組織を削除する")
    public ResponseEntity<Void> removeItem(
            @PathVariable Long folderId,
            @PathVariable Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        folderService.removeItem(userId, folderId, scopeId);
        return ResponseEntity.noContent().build();
    }
}
