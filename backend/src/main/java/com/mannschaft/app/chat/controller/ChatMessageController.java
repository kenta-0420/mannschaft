package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.service.ChatMessageService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チャットメッセージコントローラー。メッセージの送受信・編集・削除・検索APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "チャットメッセージ", description = "F04.2 チャットメッセージ管理")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService messageService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チャンネルのメッセージ一覧を取得する（カーソルベースページネーション）。
     */
    @GetMapping("/channels/{channelId}/messages")
    @Operation(summary = "メッセージ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<CursorPagedResponse<MessageResponse>> listMessages(
            @PathVariable Long channelId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit) {
        CursorPagedResponse<MessageResponse> response = messageService.listMessages(channelId, cursor, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * メッセージを送信する。
     */
    @PostMapping("/channels/{channelId}/messages")
    @Operation(summary = "メッセージ送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<MessageResponse>> sendMessage(
            @PathVariable Long channelId,
            @Valid @RequestBody SendMessageRequest request) {
        MessageResponse response = messageService.sendMessage(channelId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * メッセージを編集する。
     */
    @PatchMapping("/messages/{messageId}")
    @Operation(summary = "メッセージ編集")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "編集成功")
    public ResponseEntity<ApiResponse<MessageResponse>> editMessage(
            @PathVariable Long messageId,
            @Valid @RequestBody EditMessageRequest request) {
        MessageResponse response = messageService.editMessage(messageId, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メッセージを削除する。
     */
    @DeleteMapping("/messages/{messageId}")
    @Operation(summary = "メッセージ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId) {
        messageService.deleteMessage(messageId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * スレッド返信一覧を取得する。
     */
    @GetMapping("/messages/{messageId}/thread")
    @Operation(summary = "スレッド返信一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> listThreadReplies(
            @PathVariable Long messageId) {
        List<MessageResponse> responses = messageService.listThreadReplies(messageId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * メッセージをピン留め/解除する。
     */
    @PostMapping("/messages/{messageId}/pin")
    @Operation(summary = "ピン留めトグル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MessageResponse>> togglePin(
            @PathVariable Long messageId,
            @RequestParam boolean pinned) {
        MessageResponse response = messageService.togglePin(messageId, pinned);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メッセージを転送する。
     */
    @PostMapping("/messages/{messageId}/forward")
    @Operation(summary = "メッセージ転送")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "転送成功")
    public ResponseEntity<ApiResponse<MessageResponse>> forwardMessage(
            @PathVariable Long messageId,
            @Valid @RequestBody ForwardMessageRequest request) {
        MessageResponse response = messageService.forwardMessage(messageId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * メッセージを検索する。
     */
    @GetMapping("/channels/{channelId}/messages/search")
    @Operation(summary = "メッセージ検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検索成功")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> searchMessages(
            @PathVariable Long channelId,
            @RequestParam String keyword,
            @RequestParam(required = false) Integer limit) {
        List<MessageResponse> responses = messageService.searchMessages(channelId, keyword, limit);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
