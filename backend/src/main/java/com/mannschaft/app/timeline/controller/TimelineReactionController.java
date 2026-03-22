package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timeline.dto.ReactionRequest;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.dto.ReactionSummaryResponse;
import com.mannschaft.app.timeline.service.TimelineReactionService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * タイムラインリアクションコントローラー。リアクションの追加・削除・集計APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline/posts/{postId}/reactions")
@Tag(name = "タイムラインリアクション", description = "F04.1 投稿へのリアクション管理")
@RequiredArgsConstructor
public class TimelineReactionController {

    private final TimelineReactionService reactionService;


    /**
     * リアクションを追加する。
     */
    @PostMapping
    @Operation(summary = "リアクション追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<ReactionResponse>> addReaction(
            @PathVariable Long postId,
            @Valid @RequestBody ReactionRequest request) {
        ReactionResponse response = reactionService.addReaction(postId, request.getEmoji(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * リアクションを削除する。
     */
    @DeleteMapping
    @Operation(summary = "リアクション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeReaction(
            @PathVariable Long postId,
            @RequestParam String emoji) {
        reactionService.removeReaction(postId, emoji, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * リアクション一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "リアクション一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReactionResponse>>> getReactions(
            @PathVariable Long postId) {
        List<ReactionResponse> reactions = reactionService.getReactions(postId);
        return ResponseEntity.ok(ApiResponse.of(reactions));
    }

    /**
     * リアクション集計を取得する。
     */
    @GetMapping("/summary")
    @Operation(summary = "リアクション集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReactionSummaryResponse>>> getReactionSummary(
            @PathVariable Long postId) {
        List<ReactionSummaryResponse> summary = reactionService.getReactionSummary(postId);
        return ResponseEntity.ok(ApiResponse.of(summary));
    }
}
