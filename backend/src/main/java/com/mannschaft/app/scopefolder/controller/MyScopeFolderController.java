package com.mannschaft.app.scopefolder.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.scopefolder.dto.AddFolderItemRequest;
import com.mannschaft.app.scopefolder.dto.BulkAssignRequest;
import com.mannschaft.app.scopefolder.dto.BulkAssignResponse;
import com.mannschaft.app.scopefolder.dto.CreateFolderRequest;
import com.mannschaft.app.scopefolder.dto.FolderNotificationSummaryDto;
import com.mannschaft.app.scopefolder.dto.ReorderFoldersRequest;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.dto.UpdateFolderRequest;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.service.MyScopeFolderQueryService;
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
 *
 * <p>F15.3 で以下のエンドポイントを追加:</p>
 * <ul>
 *   <li>{@code GET /default} — 未分類フォルダ取得（lazy 生成）</li>
 *   <li>{@code POST /items/bulk-assign} — 既存所属の一括振り分け</li>
 *   <li>{@code GET /notifications/summary} — フォルダ別未読集計（タブバッジ用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/scope-folders")
@RequiredArgsConstructor
@Tag(name = "マイスコープフォルダ")
public class MyScopeFolderController {

    private final MyScopeFolderService folderService;
    private final MyScopeFolderQueryService folderQueryService;

    /**
     * フォルダ一覧を取得する（F15.3 §5.1.2: 未読件数込み）。
     */
    @SelfScopedEndpoint("検索条件が認証主体の userId に束縛される"
            + "（MyScopeFolderQueryService#getFoldersWithUnread が "
            + "MyScopeFolderRepository#findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder "
            + "のみでフォルダを引き、リクエストはフォルダ ID を受け取らない）")
    @GetMapping
    @Operation(summary = "フォルダ一覧取得",
            description = "指定スコープタイプ（TEAM/ORGANIZATION）のフォルダ一覧をアイテムID・未読件数込みで取得する")
    public ResponseEntity<ApiResponse<List<ScopeFolderResponse>>> getFolders(
            @RequestParam ScopeType scopeType) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ScopeFolderResponse> response = folderQueryService.getFoldersWithUnread(userId, scopeType);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 未分類フォルダ取得（lazy 生成）（F15.3 §5.2.1）。
     */
    @SelfScopedEndpoint("取得・生成対象が認証主体の userId に束縛される"
            + "（MyScopeFolderService#findOrCreateDefaultInternal が "
            + "findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull で引き、"
            + "生成時も userId を自身に固定する。リクエストはフォルダ ID を受け取らない）")
    @GetMapping("/default")
    @Operation(summary = "未分類フォルダ取得",
            description = "未分類フォルダを取得する。存在しない場合は lazy 生成して返す")
    public ResponseEntity<ApiResponse<ScopeFolderResponse>> getDefaultFolder(
            @RequestParam ScopeType scopeType) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeFolderResponse response = folderService.findOrCreateDefault(userId, scopeType);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダ別未読通知件数集計（F15.3 §5.2.3）。
     */
    @SelfScopedEndpoint("集計対象が認証主体の userId に束縛される"
            + "（MyScopeFolderQueryService#getNotificationSummary が呼ぶ "
            + "MyScopeFolderItemRepository#aggregateFolderUnreadCounts の WHERE 句は "
            + "folder.user_id = :userId かつ結合先通知も n.user_id = :userId。"
            + "リクエストはフォルダ ID を受け取らない）")
    @GetMapping("/notifications/summary")
    @Operation(summary = "フォルダ別未読通知件数",
            description = "フォルダ別の未読通知件数を集計して返す（タブバッジ用）")
    public ResponseEntity<ApiResponse<List<FolderNotificationSummaryDto>>> getNotificationSummary(
            @RequestParam ScopeType scopeType) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<FolderNotificationSummaryDto> response =
                folderQueryService.getNotificationSummary(userId, scopeType);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダを作成する。
     */
    @SelfScopedEndpoint("生成されるフォルダの user_id が認証主体に固定される"
            + "（MyScopeFolderService#createFolder が userId を builder に直接設定し、"
            + "上限・同名チェックも userId 絞り込みで行う。リクエストは所有者もフォルダ ID も受け取らない）")
    @PostMapping
    @Operation(summary = "フォルダ作成",
            description = "新しいスコープフォルダを作成する（1スコープタイプあたり上限20件）")
    public ResponseEntity<ApiResponse<ScopeFolderResponse>> createFolder(
            @RequestParam ScopeType scopeType,
            @Valid @RequestBody CreateFolderRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeFolderResponse response = folderService.createFolder(userId, scopeType, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 既存所属の一括振り分け（F15.3 §5.2.2）。
     * 固定パスのため /{folderId} より先に定義する。
     */
    @PostMapping("/items/bulk-assign")
    @Operation(summary = "アイテム一括振り分け",
            description = "既存所属の複数チーム/組織を指定フォルダへ一括振り分けする")
    public ResponseEntity<ApiResponse<BulkAssignResponse>> bulkAssign(
            @Valid @RequestBody BulkAssignRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        BulkAssignResponse response = folderService.bulkAssign(userId, req);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダの並び順を変更する。
     * 固定パスのため /{folderId} より先に定義する。
     */
    @SelfScopedEndpoint("並び替え対象の集合が認証主体の userId に束縛される"
            + "（MyScopeFolderService#reorderFolders は "
            + "findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder で得た自分のフォルダのみを"
            + "マップ化し、orderedIds はそのマップの引きにしか使わない。"
            + "マップに無い ID は無視されるため他人のフォルダには到達しない）")
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
    @Operation(summary = "フォルダ更新", description = "フォルダの名前・色・アイコンを更新する")
    public ResponseEntity<ApiResponse<ScopeFolderResponse>> updateFolder(
            @PathVariable Long folderId,
            @Valid @RequestBody UpdateFolderRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeFolderResponse response = folderService.updateFolder(userId, folderId, req);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダを削除する（ソフト削除）。
     */
    @DeleteMapping("/{folderId}")
    @Operation(summary = "フォルダ削除",
            description = "フォルダを論理削除する。アイテムは未分類フォルダへ自動再配置される")
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
    public ResponseEntity<ApiResponse<ScopeFolderResponse>> addItem(
            @PathVariable Long folderId,
            @Valid @RequestBody AddFolderItemRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScopeFolderResponse response = folderService.addItemWithAssignedVia(
                userId, folderId, req.scopeId(), AssignedVia.MANUAL);
        return ResponseEntity.ok(ApiResponse.of(response));
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
