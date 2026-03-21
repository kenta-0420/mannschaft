package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * タイムラインフィードコントローラー。フィード取得・検索APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline")
@Tag(name = "タイムラインフィード", description = "F04.1 タイムラインフィード取得・検索")
@RequiredArgsConstructor
public class TimelineFeedController {

    private final TimelinePostService postService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * スコープ別フィードを取得する。
     */
    @GetMapping("/feed")
    @Operation(summary = "タイムラインフィード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PostResponse>>> getFeed(
            @RequestParam(defaultValue = "PUBLIC") String scopeType,
            @RequestParam(defaultValue = "0") Long scopeId,
            @RequestParam(defaultValue = "20") int size) {
        List<PostResponse> posts = postService.getFeed(scopeType, scopeId, size);
        return ResponseEntity.ok(ApiResponse.of(posts));
    }

    /**
     * ユーザーの投稿一覧を取得する。
     */
    @GetMapping("/users/{userId}/posts")
    @Operation(summary = "ユーザー投稿一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PostResponse>>> getUserPosts(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "20") int size) {
        List<PostResponse> posts = postService.getUserPosts(userId, size);
        return ResponseEntity.ok(ApiResponse.of(posts));
    }

    /**
     * ピン留め投稿一覧を取得する。
     */
    @GetMapping("/pinned")
    @Operation(summary = "ピン留め投稿一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PostResponse>>> getPinnedPosts(
            @RequestParam(defaultValue = "PUBLIC") String scopeType,
            @RequestParam(defaultValue = "0") Long scopeId) {
        List<PostResponse> posts = postService.getPinnedPosts(scopeType, scopeId);
        return ResponseEntity.ok(ApiResponse.of(posts));
    }

    /**
     * 投稿を全文検索する。
     */
    @GetMapping("/search")
    @Operation(summary = "投稿検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検索成功")
    public ResponseEntity<ApiResponse<List<PostResponse>>> searchPosts(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        List<PostResponse> posts = postService.searchPosts(q, limit);
        return ResponseEntity.ok(ApiResponse.of(posts));
    }
}
