package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.timeline.service.TimelineScopeIdResolver;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
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
        // 認可根治 Wave6: 村スコープのピン留めは scope_id（常に 0）ではなく scope_village_id で引く。
        // 村 ID を渡さないと全村のピン留めが混在するため、フィードと同じ村 ID を必ず伝播させる。
        List<PostResponse> pinned =
                postService.getPinnedPosts(scopeType, resolvedScopeId, scopeVillageId, userId);
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
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code postService.getMyFeed} は {@code SecurityUtils.getCurrentUserId()} のみを
     * 検索条件に渡すため（cursor/limit は非識別子パラメータ）、URL・クエリに他人の識別子を
     * 指定する余地が構造的に無い（TimelineFeedController#getMyFeed）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "postService.getMyFeed(userId, ...) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（TimelineFeedController#getMyFeed）")
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
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code TimelinePostService#getUserPosts} が {@code findByUserIdVisibleToCaller} で
     * 呼び出し元（callerUserId）が所属するチーム/組織/村 ID の集合をリポジトリクエリの
     * 可視性条件として渡し、対象ユーザー（targetUserId）の投稿のうち呼び出し元から見える
     * 範囲のみを返す（{@code ContentVisibilityChecker} と同等の可視性境界をリポジトリ層で実装）。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
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
        // 村 ID を取らない EP のため scopeVillageId は null を渡す（VILLAGE 指定は fail-closed）。
        // 4 引数版を直接呼ぶのは、認可番人の委譲追跡（深さ 2）で
        // accessControlService の呼び出しが可視な位置に留まるようにするため。
        List<PostResponse> posts = postService.getPinnedPosts(scopeType, scopeId, null, userId);
        return ResponseEntity.ok(ApiResponse.of(posts));
    }

    /**
     * 投稿を全文検索する。
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code TimelinePostService#searchPosts} が呼び出し元の所属チーム/組織 ID を
     * リポジトリクエリ（{@code searchByKeyword}）の可視性条件として渡し、
     * 呼び出し元から見える範囲の投稿のみを検索対象とする。認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
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
