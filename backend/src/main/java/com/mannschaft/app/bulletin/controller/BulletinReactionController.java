package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.dto.CreateReactionRequest;
import com.mannschaft.app.bulletin.dto.ReactionResponse;
import com.mannschaft.app.bulletin.dto.ReactionSummaryResponse;
import com.mannschaft.app.bulletin.service.BulletinReactionService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 掲示板リアクションコントローラー。リアクションの追加・削除・取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/bulletin/reactions")
@Tag(name = "掲示板リアクション", description = "F05.1 掲示板リアクション管理")
@RequiredArgsConstructor
public class BulletinReactionController {

    private final BulletinReactionService reactionService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * リアクションを追加する。
     */
    @PostMapping
    @Operation(summary = "リアクション追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<ReactionResponse>> addReaction(
            @Valid @RequestBody CreateReactionRequest request) {
        ReactionResponse response = reactionService.addReaction(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * リアクションを削除する。
     */
    @DeleteMapping
    @Operation(summary = "リアクション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeReaction(
            @RequestParam String targetType,
            @RequestParam Long targetId,
            @RequestParam String emoji) {
        reactionService.removeReaction(getCurrentUserId(), targetType, targetId, emoji);
        return ResponseEntity.noContent().build();
    }

    /**
     * リアクション一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "リアクション一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReactionResponse>>> listReactions(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        List<ReactionResponse> responses = reactionService.listReactions(targetType, targetId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * リアクション集計を取得する。
     */
    @GetMapping("/summary")
    @Operation(summary = "リアクション集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReactionSummaryResponse>>> getReactionSummary(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        List<ReactionSummaryResponse> responses = reactionService.getReactionSummary(targetType, targetId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
