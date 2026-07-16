package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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
        Long userId = SecurityUtils.getCurrentUserId();
        Long resolvedScopeId = scopeIdResolver.resolve(scopeType, scopeId);
        List<PostResponse> posts = postService.getFeed(scopeType, resolvedScopeId, scopeVillageId, size, userId);
        List<PostResponse> pinned = postService.getPinnedPosts(scopeType, resolvedScopeId, userId);
        TimelineFeedResponse response = TimelineFeedResponse.of(pinned, posts, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 個人ダッシュボード集約タイムライン（マイフィード）を取得する。
     *
     * <p>ログインユーザーが所属する全チーム/組織（MEMBER / SUPPORTER 両方）の投稿を
     * 横断集約し、新しい順で返す。VILLAGE は集約対象外。自分の投稿も含む。</p>
     *
     * <p>認証必須: 本 EP は SecurityConfig の permitAll に含めない（deny-by-default で
     * 未認証は 401）。{@link SecurityUtils#getCurrentUserId()} がトークンからユーザー ID を取得する。</p>
     *
     * <p>{@code /feed} と異なり pinned は常に空（data.pinned=[]）で、id キーセットの
     * 実カーソル（meta.nextCursor）を返す。クエリパラメータは FE の {@code getMyTimeline}
     * が送る {@code cursor} / {@code limit} に一致させる（{@code /feed} の {@code size} ではない）。</p>
     *
     * @param cursor カーソル（この投稿 id 未満を取得）。未指定なら最新から
     * @param limit  取得件数（既定 20）
     * @return マイフィード（pinned 空・実カーソル付き）
     */
    @GetMapping("/my")
    @Operation(summary = "個人集約タイムライン取得（所属team/org横断）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<TimelineFeedResponse> getMyFeed(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PostResponse> posts = postService.getMyFeed(userId, cursor, limit);
        return ResponseEntity.ok(TimelineFeedResponse.ofMyFeed(posts, limit));
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
        Long callerUserId = SecurityUtils.getCurrentUserId();
        List<PostResponse> posts = postService.getUserPosts(userId, size, callerUserId);
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
        Long userId = SecurityUtils.getCurrentUserId();
        List<PostResponse> posts = postService.getPinnedPosts(scopeType, scopeId, userId);
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
        Long userId = SecurityUtils.getCurrentUserId();
        List<PostResponse> posts = postService.searchPosts(q, limit, userId);
        return ResponseEntity.ok(ApiResponse.of(posts));
    }
}
