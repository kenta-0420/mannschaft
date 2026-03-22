package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.dashboard.dto.AssignFolderItemRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignFolderItemsRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignResultResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.CreateChatFolderRequest;
import com.mannschaft.app.dashboard.dto.UpdateChatFolderRequest;
import com.mannschaft.app.dashboard.service.ChatFolderService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

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
    @DeleteMapping("/{id}")
    @Operation(summary = "フォルダ削除", description = "カスタムフォルダを削除する（配下アイテムは未分類に戻る）")
    public ResponseEntity<Void> deleteFolder(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        chatFolderService.deleteFolder(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * フォルダにアイテムを割り当てる。
     */
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
     * フォルダにアイテムを一括割り当てする。
     */
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
