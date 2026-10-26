package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.dto.FriendFeedResponse;
import com.mannschaft.app.social.service.FriendFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理者フィード REST コントローラ（F01.5 Phase 2）。
 *
 * <p>
 * 相互フォロー成立済みフレンドチームの {@code share_with_friends = TRUE} 投稿を
 * カーソルベースページングで一覧返却する。
 * 全エンドポイントで ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を必要とする。
 * </p>
 *
 * <p>
 * エンドポイント一覧:
 * </p>
 *
 * <ul>
 *   <li>{@code GET /api/v1/teams/{teamId}/friend-feed}</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{teamId}/friend-feed")
@Tag(name = "管理者フィード", description = "F01.5 Phase 2 フレンドチーム投稿フィード取得API")
@RequiredArgsConstructor
public class FriendFeedController {

    private final FriendFeedService friendFeedService;

    // ═════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/friend-feed — フィード取得
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンドチームのフィード投稿一覧を取得する。
     *
     * <p>
     * 相互フォロー成立済みフレンドチームの {@code share_with_friends = TRUE} 投稿を
     * 降順で返す。カーソルページング対応。フォルダ・発信元チーム・転送済みのみでフィルタ可能。
     * </p>
     *
     * @param teamId       自チーム ID
     * @param folderId     フォルダフィルタ（省略可）
     * @param sourceTeamId 発信元チームフィルタ（省略可）
     * @param forwardedOnly 転送済みのみを返す（省略可。デフォルト false）
     * @param cursor       カーソル（省略時は先頭から取得）
     * @param limit        最大取得件数（省略時 20。最大 50）
     * @return フィード投稿一覧レスポンス（200 OK）
     */
    @GetMapping
    @Operation(
            summary = "管理者フィード一覧取得",
            description = "フレンドチームの share_with_friends=TRUE 投稿を降順で返す。"
                    + "folderId で特定フォルダのフレンドに絞り込み可能。"
                    + "cursor ページングにより大量データを分割取得できる。"
                    + "ADMIN または MANAGE_FRIEND_TEAMS 権限が必要。")
    public ResponseEntity<FriendFeedResponse> getFeed(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) Long sourceTeamId,
            @RequestParam(required = false) Boolean forwardedOnly,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {

        Long userId = SecurityUtils.getCurrentUserId();
        FriendFeedResponse response = friendFeedService.getFeed(
                teamId, userId, folderId, sourceTeamId, forwardedOnly, cursor, limit);
        return ResponseEntity.ok(response);
    }
}
