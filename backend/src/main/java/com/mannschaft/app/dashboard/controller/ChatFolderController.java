package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.dashboard.dto.AssignFolderItemRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignFolderItemsRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignResultResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.CreateChatFolderRequest;
import com.mannschaft.app.dashboard.dto.FolderItemResponse;
import com.mannschaft.app.dashboard.dto.UpdateChatFolderRequest;
import com.mannschaft.app.dashboard.dto.UpdateFolderItemRequest;
import com.mannschaft.app.dashboard.service.ChatFolderService;
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
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * チャット・連絡先フォルダコントローラー。
 * カスタムフォルダのCRUD、アイテムの割り当て/解除/一括割り当てを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat-folders")
@Tag(name = "チャットフォルダ")
@RequiredArgsConstructor
public class ChatFolderController {

    private final ChatFolderService chatFolderService;

    /**
     * カスタムフォルダ一覧を取得する。
     */
    @SelfScopedEndpoint("chatFolderService.getFolders の検索キーが SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（ChatFolderController.java:51）")
    @GetMapping
    @Operation(summary = "フォルダ一覧", description = "ユーザーのカスタムフォルダ一覧を取得する")
    public ResponseEntity<ApiResponse<List<ChatFolderResponse>>> getFolders() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ChatFolderResponse> response = chatFolderService.getFolders(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カスタムフォルダを作成する。
     */
    @SelfScopedEndpoint("chatFolderService.createFolder が作成するフォルダの userId は常に"
            + "SecurityUtils.getCurrentUserId() で固定され、リクエストで所有者を指定する余地が無い"
            + "（ChatFolderController.java:63）")
    @PostMapping
    @Operation(summary = "フォルダ作成", description = "新しいカスタムフォルダを作成する（上限20件）")
    public ResponseEntity<ApiResponse<ChatFolderResponse>> createFolder(
            @Valid @RequestBody CreateChatFolderRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ChatFolderResponse response = chatFolderService.createFolder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * カスタムフォルダを更新する。
     */
    // 認可根治戦役 Wave4 ロットD: chatFolderService.updateFolder は冒頭で
    // findOwnedFolder(userId, folderId)（ChatFolderService.java:263-271）を通し、
    // folderRepository.findByIdAndUserId で他ユーザー所有フォルダを DASHBOARD_006/007 として拒否する。
    @AuthorizedInService
    @PutMapping("/{id}")
    @Operation(summary = "フォルダ更新", description = "カスタムフォルダの名前・アイコン・色・並び順を更新する")
    public ResponseEntity<ApiResponse<ChatFolderResponse>> updateFolder(
            @PathVariable Long id,
            @Valid @RequestBody UpdateChatFolderRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ChatFolderResponse response = chatFolderService.updateFolder(userId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * カスタムフォルダを削除する。
     */
    // 認可根治戦役 Wave4 ロットD: chatFolderService.deleteFolder が findOwnedFolder(userId, folderId)
    // （ChatFolderService.java:263-271）で所有者検証してから削除する。
    @AuthorizedInService
    @DeleteMapping("/{id}")
    @Operation(summary = "フォルダ削除", description = "カスタムフォルダを削除する（配下アイテムは未分類に戻る）")
    public ResponseEntity<Void> deleteFolder(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        chatFolderService.deleteFolder(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * フォルダ内のアイテム一覧を取得する。
     */
    // 認可根治戦役 Wave4 ロットD: chatFolderService.getFolderItems が冒頭で
    // findOwnedFolder(userId, folderId)（ChatFolderService.java:263-271）を通す。
    @AuthorizedInService
    @GetMapping("/{id}/items")
    @Operation(summary = "フォルダアイテム一覧",
            description = "フォルダ内のアイテム一覧を取得する。sort=LAST_MESSAGE で最終DM日時降順")
    public ResponseEntity<ApiResponse<List<FolderItemResponse>>> getFolderItems(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "") String sort) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<FolderItemResponse> response = chatFolderService.getFolderItems(userId, id, sort);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダにアイテムを割り当てる。
     */
    // 認可根治戦役 Wave4 ロットD: chatFolderService.assignItem が冒頭で
    // findOwnedFolder(userId, folderId)（ChatFolderService.java:263-271）を通す。
    @AuthorizedInService
    @PutMapping("/{id}/items")
    @Operation(summary = "アイテム割り当て", description = "フォルダにDM / 連絡先を割り当てる（既に別フォルダの場合は移動）")
    public ResponseEntity<ApiResponse<ChatFolderResponse>> assignItem(
            @PathVariable Long id,
            @Valid @RequestBody AssignFolderItemRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ChatFolderResponse response = chatFolderService.assignItem(userId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダからアイテムを外す。
     */
    // 認可根治戦役 Wave4 ロットD: chatFolderService.removeItem がアイテム所属フォルダを解決したうえで
    // findOwnedFolder(userId, item.getFolderId())（ChatFolderService.java:182, 263-271）を通してから
    // 削除する。所有者不一致の場合は findOwnedFolder が例外を投げ、削除は実行されない。
    @AuthorizedInService
    @DeleteMapping("/items/{itemType}/{itemId}")
    @Operation(summary = "アイテム解除", description = "フォルダからアイテムを外して未分類に戻す")
    public ResponseEntity<Void> removeItem(
            @PathVariable String itemType,
            @PathVariable Long itemId) {
        Long userId = SecurityUtils.getCurrentUserId();
        chatFolderService.removeItem(userId, itemType, itemId);
        return ResponseEntity.noContent().build();
    }

    /**
     * フォルダアイテムの属性（カスタム表示名・ピン留め・メモ）を更新する。
     * CONTACT タイプのみ対象。
     */
    // 認可根治戦役 Wave4 ロットD: chatFolderService.updateItemAttributes がアイテム所属フォルダを解決し
    // findOwnedFolder(userId, item.getFolderId())（ChatFolderService.java:249, 263-271）を通してから
    // 属性を更新する。
    @AuthorizedInService
    @PatchMapping("/items/{itemType}/{itemId}")
    @Operation(summary = "アイテム属性更新", description = "連絡先のカスタム表示名・ピン留め・プライベートメモを更新する（CONTACT のみ）")
    public ResponseEntity<ApiResponse<FolderItemResponse>> updateItemAttributes(
            @PathVariable String itemType,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateFolderItemRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        FolderItemResponse response = chatFolderService.updateItemAttributes(userId, itemType, itemId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フォルダにアイテムを一括割り当てする。
     */
    // 認可根治戦役 Wave4 ロットD: chatFolderService.bulkAssignItems が冒頭で
    // findOwnedFolder(userId, folderId)（ChatFolderService.java:195, 263-271）を通してから
    // 一括割り当てを行う。対象フォルダは 1 件のみで、リクエスト内の各アイテムは全て
    // 同一の認可済みフォルダへ割り当てられる（フォルダ単位で認可済みのため 1 件ずつの
    // 追加認可呼び出しは不要）。
    @AuthorizedInService
    @PutMapping("/{id}/items/bulk")
    @Operation(summary = "アイテム一括割り当て", description = "複数のDM / 連絡先をフォルダに一括割り当てする（最大20件）")
    public ResponseEntity<ApiResponse<BulkAssignResultResponse>> bulkAssignItems(
            @PathVariable Long id,
            @Valid @RequestBody BulkAssignFolderItemsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        BulkAssignResultResponse response = chatFolderService.bulkAssignItems(userId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
