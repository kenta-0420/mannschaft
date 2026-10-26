package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.service.TimelineReactionService;
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
 * タイムラインリアクション（みたよ！）コントローラー。みたよ！の追加・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline/posts/{postId}/reactions")
@Tag(name = "タイムラインリアクション", description = "F04.1 投稿への「みたよ！」管理")
@RequiredArgsConstructor
public class TimelineReactionController {

    private final TimelineReactionService reactionService;

    /**
     * 「みたよ！」を追加する。
     */
    @PostMapping
    @Operation(summary = "みたよ！追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<ReactionResponse>> addReaction(
            @PathVariable Long postId) {
        ReactionResponse response = reactionService.addReaction(postId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 「みたよ！」を削除する。
     */
    @DeleteMapping
    @Operation(summary = "みたよ！削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<ReactionResponse>> removeReaction(
            @PathVariable Long postId) {
        ReactionResponse response = reactionService.removeReaction(postId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
