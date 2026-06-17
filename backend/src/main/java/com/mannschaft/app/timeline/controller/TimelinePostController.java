package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.UpdatePostRequest;
import com.mannschaft.app.timeline.service.TimelinePostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * タイムライン投稿コントローラー。投稿のCRUD・リプライ・ピン留めAPIを提供する。
 *
 * <p>scopeId の slug 解決は {@link TeamService#resolveTeamId} /
 * {@link OrganizationService#resolveOrgId} のService経由で行い、
 * Repository直注入しない（ドメイン境界原則）。</p>
 */
@RestController
@RequestMapping("/api/v1/timeline/posts")
@Tag(name = "タイムライン投稿", description = "F04.1 タイムライン投稿CRUD")
@RequiredArgsConstructor
public class TimelinePostController {

    private final TimelinePostService postService;
    /** scopeId の slug 解決用: ドメイン境界原則によりServiceを経由する（Repository直注入禁止）。 */
    private final TeamService teamService;
    /** scopeId の slug 解決用: ドメイン境界原則によりServiceを経由する（Repository直注入禁止）。 */
    private final OrganizationService organizationService;


    /**
     * 投稿を作成する。
     *
     * <p>scopeId はチーム/組織ページの URL slug（例: "fc-u-18"）または数値 ID 文字列を受け付ける。
     * slug が渡された場合（{@link CreatePostRequest#getScopeId()} が null かつ
     * {@link CreatePostRequest#getScopeIdRaw()} に slug が格納）は、
     * {@link TeamService#resolveTeamId} / {@link OrganizationService#resolveOrgId} で
     * 内部 ID に解決してから Service を呼び出す。</p>
     */
    @PostMapping
    @Operation(summary = "投稿作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PostResponse>> createPost(
            @Valid @RequestBody CreatePostRequest request) {
        CreatePostRequest resolvedRequest = request;
        // scopeId が null かつ scopeIdRaw に slug 文字列がある場合 → slug 解決を試みる
        if (request.getScopeId() == null && request.getScopeIdRaw() != null
                && request.getScopeType() != null) {
            String scopeType = request.getScopeType();
            String slugOrId = request.getScopeIdRaw();
            Long resolvedId = resolveScopeId(scopeType, slugOrId);
            resolvedRequest = request.withResolvedScopeId(resolvedId);
        }
        PostResponse response = postService.createPost(resolvedRequest, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スコープID文字列（slug または Long文字列）を内部Long IDに解決する。
     * {@link com.mannschaft.app.timeline.controller.TimelineFeedController} と同じパターン。
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
            try {
                if ("TEAM".equals(scopeType)) {
                    return teamService.resolveTeamId(scopeIdStr);
                } else {
                    return organizationService.resolveOrgId(scopeIdStr);
                }
            } catch (BusinessException ex) {
                throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
            }
        }
    }

    /**
     * 投稿詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "投稿詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPost(@PathVariable Long id) {
        PostDetailResponse response = postService.getPostDetail(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 投稿を更新する。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "投稿更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PostResponse>> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request) {
        PostResponse response = postService.updatePost(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 投稿を削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "投稿削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePost(@PathVariable Long id) {
        postService.deletePost(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 投稿のリプライ一覧を取得する。
     */
    @GetMapping("/{id}/replies")
    @Operation(summary = "リプライ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PostResponse>>> getReplies(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int size) {
        List<PostResponse> replies = postService.getReplies(id, size);
        return ResponseEntity.ok(ApiResponse.of(replies));
    }

    /**
     * 投稿のピン留め状態を切り替える。
     */
    @PostMapping("/{id}/pin")
    @Operation(summary = "ピン留め切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<ApiResponse<PostResponse>> togglePin(
            @PathVariable Long id,
            @RequestParam boolean pinned) {
        PostResponse response = postService.togglePin(id, pinned, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
