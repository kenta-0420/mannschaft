package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.dto.FollowTeamRequest;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.dto.PastForwardHandling;
import com.mannschaft.app.social.dto.SetVisibilityRequest;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import com.mannschaft.app.social.dto.UnfollowRequest;
import com.mannschaft.app.social.service.TeamFriendsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

/**
 * フレンドチーム関係 REST コントローラ（F01.5 Phase 1）。
 *
 * <p>
 * 以下のエンドポイントを提供する:
 * </p>
 *
 * <ul>
 *   <li>{@code POST   /api/v1/teams/{id}/friends/follow} — 他チームをフォロー</li>
 *   <li>{@code DELETE /api/v1/teams/{id}/friends/follow/{targetTeamId}} — フォロー解除</li>
 *   <li>{@code GET    /api/v1/teams/{id}/friends} — フレンドチーム一覧取得</li>
 *   <li>{@code PATCH  /api/v1/teams/{id}/friends/{teamFriendId}/visibility} — 公開設定変更</li>
 * </ul>
 *
 * <p>
 * 認可は {@link TeamFriendsService} 側で権限チェックを行い、違反時は
 * {@code GlobalExceptionHandler} が適切な HTTP ステータス（403/404/409/等）に変換する。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{id}/friends")
@Tag(name = "フレンドチーム管理", description = "F01.5 Phase 1 チーム間フォロー・フレンド関係管理")
@RequiredArgsConstructor
public class TeamFriendsController {

    /** フレンド一覧のページング上限 */
    private static final int MAX_PAGE_SIZE = 100;

    /** フレンド一覧のデフォルトページサイズ */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** チームスコープ識別子 */
    private static final String SCOPE_TEAM = "TEAM";

    private final TeamFriendsService teamFriendsService;
    private final AccessControlService accessControlService;

    // ═════════════════════════════════════════════════════════════
    // POST /follow — 他チームをフォロー
    // ═════════════════════════════════════════════════════════════

    /**
     * 指定した他チームをフォローする。相互フォロー成立時にはフレンド関係も自動生成される。
     *
     * <p>
     * NOWAIT 競合発生時は {@code 202 Accepted} を返し、{@code retryAfterSeconds}
     * をクライアントに通知する。それ以外の成功時は {@code 201 Created}。
     * </p>
     *
     * @param teamId  パスパラメータ {@code {id}} 自チーム ID
     * @param request リクエストボディ
     * @return フォロー結果レスポンス
     */
    @PostMapping("/follow")
    @Operation(summary = "他チームをフォロー",
            description = "相手が既に自チームをフォロー済みの場合は自動的にフレンド関係が成立する。"
                    + "NOWAIT 競合時は 202 Accepted + retryAfterSeconds を返す。")
    public ResponseEntity<ApiResponse<FollowTeamResponse>> follow(
            @PathVariable("id") Long teamId,
            @Valid @RequestBody FollowTeamRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        FollowTeamResponse response = teamFriendsService.follow(
                teamId, request.getTargetTeamId(), userId);

        HttpStatus status = (response.getRetryAfterSeconds() != null)
                ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.of(response));
    }

    // ═════════════════════════════════════════════════════════════
    // DELETE /follow/{targetTeamId} — フォロー解除
    // ═════════════════════════════════════════════════════════════

    /**
     * 指定チームへのフォローを解除する。相互フォロー状態だった場合はフレンド関係も
     * 自動削除される。過去転送投稿の扱いは {@link UnfollowRequest} で指定可能。
     *
     * @param teamId       自チーム ID
     * @param targetTeamId フォロー解除先チーム ID
     * @param request      リクエストボディ（省略可）
     * @return 204 No Content
     */
    @DeleteMapping("/follow/{targetTeamId}")
    @Operation(summary = "フォロー解除（フレンド関係も連動解除）",
            description = "相互フォロー解除時、過去転送投稿の扱い（KEEP / SOFT_DELETE / ARCHIVE）を指定可能。")
    public ResponseEntity<Void> unfollow(
            @PathVariable("id") Long teamId,
            @PathVariable("targetTeamId") Long targetTeamId,
            @RequestBody(required = false) UnfollowRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PastForwardHandling mode = (request != null)
                ? request.getEffectiveMode()
                : PastForwardHandling.KEEP;

        teamFriendsService.unfollow(teamId, targetTeamId, mode, userId);
        return ResponseEntity.noContent().build();
    }

    // ═════════════════════════════════════════════════════════════
    // GET / — フレンドチーム一覧取得
    // ═════════════════════════════════════════════════════════════

    /**
     * 自チームのフレンドチーム一覧を取得する。
     *
     * <p>
     * SUPPORTER ロール保持者には自動的に {@code is_public = TRUE} のフレンドのみを
     * 返却する。それ以外のメンバー（MEMBER / DEPUTY_ADMIN / ADMIN）は全件取得可能。
     * Phase 1 はオフセットページングで実装（Phase 3 でカーソルページングへ移行）。
     * </p>
     *
     * @param teamId 自チーム ID
     * @param page   ページ番号（0 始まり、デフォルト 0）
     * @param size   1 ページあたりの件数（デフォルト 20、最大 100）
     * @return フレンドチーム一覧レスポンス
     */
    @GetMapping
    @Operation(summary = "フレンドチーム一覧取得",
            description = "SUPPORTER には is_public = TRUE のフレンドのみ返却。"
                    + "その他のロールは全件取得可能。")
    public ResponseEntity<TeamFriendListResponse> listFriends(
            @PathVariable("id") Long teamId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long userId = SecurityUtils.getCurrentUserId();

        // Controller 層で形式バリデーション
        if (page < 0) {
            page = 0;
        }
        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        boolean publicOnly = isSupporterOnly(userId, teamId);

        Pageable pageable = PageRequest.of(page, effectiveSize,
                Sort.by(Sort.Direction.DESC, "establishedAt"));
        TeamFriendListResponse response = teamFriendsService
                .listFriendsResponse(teamId, userId, pageable, publicOnly);
        return ResponseEntity.ok(response);
    }

    /**
     * 閲覧者が SUPPORTER のみ権限を持つかどうかを判定する。
     * SUPPORTER は {@code is_public = TRUE} のフレンドのみ閲覧可能の仕様。
     *
     * @param userId ユーザー ID
     * @param teamId チーム ID
     * @return SUPPORTER のみ（MEMBER 以上でない）の場合 {@code true}
     */
    private boolean isSupporterOnly(Long userId, Long teamId) {
        String roleName = accessControlService.getRoleName(userId, teamId, SCOPE_TEAM);
        return "SUPPORTER".equals(roleName);
    }

    // ═════════════════════════════════════════════════════════════
    // PATCH /{teamFriendId}/visibility — 公開設定変更
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンド関係の公開設定を変更する。ADMIN のみ実行可能（DEPUTY_ADMIN は 403）。
     *
     * @param teamId       自チーム ID
     * @param teamFriendId フレンド関係 ID
     * @param request      リクエストボディ
     * @return 204 No Content
     */
    @PatchMapping("/{teamFriendId}/visibility")
    @Operation(summary = "フレンド関係の公開設定変更",
            description = "ADMIN のみ実行可能。Phase 1 は単独承認型。")
    public ResponseEntity<Void> setVisibility(
            @PathVariable("id") Long teamId,
            @PathVariable("teamFriendId") Long teamFriendId,
            @Valid @RequestBody SetVisibilityRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        teamFriendsService.setVisibility(teamId, teamFriendId,
                Boolean.TRUE.equals(request.getIsPublic()), userId);
        return ResponseEntity.noContent().build();
    }
}
