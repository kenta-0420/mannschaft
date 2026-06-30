package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.timeline.service.TimelineScopeIdResolver;
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
import java.util.UUID;

/**
 * タイムラインフィードコントローラー。フィード取得・検索APIを提供する。
 *
 * <p>slug解決は {@link TimelineScopeIdResolver} に委譲する。投稿作成（書き込み）経路と
 * 共有することで、読み書きで解決ロジックが乖離しない（書き込み側だけ slug 未対応で 400、
 * の非対称を防ぐ）。リゾルバ内部は {@code TeamService}/{@code OrganizationService} 経由で
 * 解決し、Repository を直注入しない（ドメイン境界原則）。</p>
 */
@RestController
@RequestMapping("/api/v1/timeline")
@Tag(name = "タイムラインフィード", description = "F04.1 タイムラインフィード取得・検索")
@RequiredArgsConstructor
public class TimelineFeedController {

    private final TimelinePostService postService;
    /** slug/Long 文字列 → 内部 Long ID の共有リゾルバ（書き込み経路と共通）。 */
    private final TimelineScopeIdResolver scopeIdResolver;

    /**
     * スコープ別フィードを取得する。
     *
     * <p>scopeType=VILLAGE の場合は scopeVillageId を指定すること。
     * scopeVillageId が省略された場合は空リストを返す。</p>
     *
     * <p>scopeId はチーム/組織ページの URL で使われる slug 文字列または
     * 内部 Long ID 文字列を受け付ける。slug の場合は {@link TimelineScopeIdResolver} 経由で
     * 内部 ID に変換する。</p>
     *
     * <p>レスポンス形式: {@code { "data": { "pinned": [...], "posts": [...] }, "meta": { ... } }}</p>
     */
    @GetMapping("/feed")
    @Operation(summary = "タイムラインフィード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<TimelineFeedResponse> getFeed(
            @RequestParam(defaultValue = "PUBLIC") String scopeType,
            @RequestParam(defaultValue = "0") String scopeId,
            @RequestParam(required = false) UUID scopeVillageId,
            @RequestParam(defaultValue = "20") int size) {
        Long resolvedScopeId = scopeIdResolver.resolve(scopeType, scopeId);
        List<PostResponse> posts = postService.getFeed(scopeType, resolvedScopeId, scopeVillageId, size);
        List<PostResponse> pinned = postService.getPinnedPosts(scopeType, resolvedScopeId);
        TimelineFeedResponse response = TimelineFeedResponse.of(pinned, posts, size);
        return ResponseEntity.ok(response);
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
