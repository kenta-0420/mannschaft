package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.AddReactionRequest;
import com.mannschaft.app.chat.dto.ReactionResponse;
import com.mannschaft.app.chat.service.ChatReactionService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チャットリアクションコントローラー。リアクションの追加・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat/messages/{messageId}/reactions")
@Tag(name = "チャットリアクション", description = "F04.2 メッセージリアクション管理")
@RequiredArgsConstructor
public class ChatReactionController {

    private final ChatReactionService reactionService;


    /**
     * リアクションを追加する。
     */
    @PostMapping
    @Operation(summary = "リアクション追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<ReactionResponse>> addReaction(
            @PathVariable Long messageId,
            @Valid @RequestBody AddReactionRequest request) {
        ReactionResponse response = reactionService.addReaction(messageId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * リアクションを削除する。
     */
    @DeleteMapping
    @Operation(summary = "リアクション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeReaction(
            @PathVariable Long messageId,
            @RequestParam String emoji) {
        reactionService.removeReaction(messageId, emoji, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
