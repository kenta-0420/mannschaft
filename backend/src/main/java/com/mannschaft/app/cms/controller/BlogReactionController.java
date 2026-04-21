package com.mannschaft.app.cms.controller;

import com.mannschaft.app.cms.dto.BlogReactionResponse;
import com.mannschaft.app.cms.service.BlogReactionService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ブログ記事リアクション（みたよ！）コントローラー。みたよ！の追加・削除APIを提供する。
 * 認証必須（未ログインは403）。
 */
@RestController
@RequestMapping("/api/v1/blog/posts/{postId}/reactions")
@Tag(name = "ブログリアクション", description = "ブログ記事への「みたよ！」管理")
@RequiredArgsConstructor
public class BlogReactionController {

    private final BlogReactionService reactionService;

    /**
     * 「みたよ！」を追加する。
     */
    @PostMapping
    @Operation(summary = "ブログみたよ！追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<BlogReactionResponse>> addReaction(
            @PathVariable Long postId) {
        BlogReactionResponse response = reactionService.addReaction(postId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 「みたよ！」を削除する。
     */
    @DeleteMapping
    @Operation(summary = "ブログみたよ！削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<BlogReactionResponse>> removeReaction(
            @PathVariable Long postId) {
        BlogReactionResponse response = reactionService.removeReaction(postId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
