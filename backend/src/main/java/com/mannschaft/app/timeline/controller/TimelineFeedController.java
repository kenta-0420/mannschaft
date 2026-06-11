package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.TimelineErrorCode;
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
import java.util.UUID;

/**
 * タイムラインフィードコントローラー。フィード取得・検索APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline")
@Tag(name = "タイムラインフィード", description = "F04.1 タイムラインフィード取得・検索")
@RequiredArgsConstructor
public class TimelineFeedController {

    private final TimelinePostService postService;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * スコープ別フィードを取得する。
     *
     * <p>scopeType=VILLAGE の場合は scopeVillageId を指定すること。
     * scopeVillageId が省略された場合は空リストを返す。</p>
     *
     * <p>scopeId はチーム/組織ページの URL で使われる slug 文字列または
     * 内部 Long ID 文字列を受け付ける。slug の場合は {@link TeamRepository#findBySlugAndDeletedAtIsNull} /
     * {@link OrganizationRepository#findBySlugAndDeletedAtIsNull} で内部 ID に変換する。</p>
     */
    @GetMapping("/feed")
    @Operation(summary = "タイムラインフィード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PostResponse>>> getFeed(
            @RequestParam(defaultValue = "PUBLIC") String scopeType,
            @RequestParam(defaultValue = "0") String scopeId,
            @RequestParam(required = false) UUID scopeVillageId,
            @RequestParam(defaultValue = "20") int size) {
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        List<PostResponse> posts = postService.getFeed(scopeType, resolvedScopeId, scopeVillageId, size);
        return ResponseEntity.ok(ApiResponse.of(posts));
    }

    /**
     * スコープID文字列（slug または Long文字列）を内部Long IDに解決する。
     *
     * <p>TEAM/ORGANIZATION スコープの場合: Long 文字列はそのまま返し、それ以外は slug として
     * {@link TeamRepository#findBySlugAndDeletedAtIsNull} /
     * {@link OrganizationRepository#findBySlugAndDeletedAtIsNull} で内部 ID を引く。
     * 未存在の場合は {@link BusinessException} をスローする。</p>
     *
     * <p>その他のスコープ: Long 文字列はそのまま返し、変換不能な場合は {@code 0L} を返す。</p>
     *
     * @param scopeType    スコープ種別（例: "TEAM", "ORGANIZATION", "PUBLIC"）
     * @param scopeIdStr   スコープID文字列（slug または Long 文字列）
     * @return 内部Long ID
     */
    private Long resolveScopeId(String scopeType, String scopeIdStr) {
        if (!"TEAM".equals(scopeType) && !"ORGANIZATION".equals(scopeType)) {
            try {
                return Long.parseLong(scopeIdStr);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        try {
            return Long.parseLong(scopeIdStr);
        } catch (NumberFormatException e) {
            // 数値でない場合はスラッグとして解決する
            if ("TEAM".equals(scopeType)) {
                return teamRepository.findBySlugAndDeletedAtIsNull(scopeIdStr)
                        .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND))
                        .getId();
            } else {
                return organizationRepository.findBySlugAndDeletedAtIsNull(scopeIdStr)
                        .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND))
                        .getId();
            }
        }
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
