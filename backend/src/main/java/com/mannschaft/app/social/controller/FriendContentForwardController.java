package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.dto.ForwardRequest;
import com.mannschaft.app.social.dto.ForwardResponse;
import com.mannschaft.app.social.dto.FriendForwardExportListResponse;
import com.mannschaft.app.social.service.FriendContentForwardService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * フレンドコンテンツ転送 REST コントローラ（F01.5 Phase 1）。
 *
 * <p>
 * 管理者フィードの投稿転送・取消・逆転送履歴参照の 3 エンドポイントを提供する。
 * 全エンドポイントで ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を必要とする。
 * </p>
 *
 * <p>
 * エンドポイント一覧:
 * </p>
 *
 * <ul>
 *   <li>{@code POST   /api/v1/teams/{id}/friend-feed/{postId}/forward}</li>
 *   <li>{@code DELETE /api/v1/teams/{id}/friend-feed/forwards/{forwardId}}</li>
 *   <li>{@code GET    /api/v1/teams/{id}/friend-forward-exports}</li>
 * </ul>
 *
 * <p>
 * Phase 1 では {@code target=MEMBER} のみ受理し、{@code MEMBER_AND_SUPPORTER} は
 * Service 層で 400 エラーを返す（設計書 §3 / §5 / §6）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{id}")
@Tag(name = "フレンドコンテンツ転送", description = "F01.5 Phase 1 フレンド投稿転送・取消・透明性API")
@RequiredArgsConstructor
public class FriendContentForwardController {

    /** 逆転送履歴のデフォルトページサイズ */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 逆転送履歴の最大ページサイズ */
    private static final int MAX_PAGE_SIZE = 100;

    private final FriendContentForwardService forwardService;

    // ═════════════════════════════════════════════════════════════
    // POST /friend-feed/{postId}/forward — 転送実行
    // ═════════════════════════════════════════════════════════════

    /**
     * 管理者フィードの投稿を自チーム内タイムラインへ転送する。
     *
     * @param teamId  自チーム ID
     * @param postId  転送元投稿 ID
     * @param request 転送リクエスト
     * @return 転送結果レスポンス（201 Created）
     */
    @PostMapping("/friend-feed/{postId}/forward")
    @Operation(summary = "フレンド投稿を自チームに転送",
            description = "転送元投稿を自チーム内タイムラインへ再配信する。"
                    + "冪等性は UNIQUE 制約で担保（二重転送時は 409）。Phase 1 は MEMBER 固定。")
    public ResponseEntity<ApiResponse<ForwardResponse>> forward(
            @PathVariable("id") Long teamId,
            @PathVariable("postId") Long postId,
            @Valid @RequestBody ForwardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ForwardResponse response = forwardService.forward(teamId, postId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ═════════════════════════════════════════════════════════════
    // DELETE /friend-feed/forwards/{forwardId} — 転送取消
    // ═════════════════════════════════════════════════════════════

    /**
     * 転送を取消する。転送先 {@code timeline_posts} は論理削除される。
     *
     * @param teamId    自チーム ID
     * @param forwardId 転送履歴 ID
     * @return 204 No Content
     */
    @DeleteMapping("/friend-feed/forwards/{forwardId}")
    @Operation(summary = "転送取消",
            description = "指定転送履歴の is_revoked を TRUE にし、転送先投稿を論理削除する。"
                    + "取消後は同一投稿を再度転送可能。")
    public ResponseEntity<Void> revoke(
            @PathVariable("id") Long teamId,
            @PathVariable("forwardId") Long forwardId) {
        Long userId = SecurityUtils.getCurrentUserId();
        forwardService.revoke(teamId, forwardId, userId);
        return ResponseEntity.noContent().build();
    }

    // ═════════════════════════════════════════════════════════════
    // GET /friend-forward-exports — 逆転送履歴取得（透明性確保用API）
    // ═════════════════════════════════════════════════════════════

    /**
     * 自チーム投稿が他フレンドチームへ転送された履歴一覧を取得する（透明性確保用）。
     * 非公開フレンドは転送先チーム名が {@code "匿名チーム"} に匿名化される。
     *
     * @param teamId 自チーム ID
     * @param page   ページ番号（0 始まり）
     * @param size   1 ページあたりの件数
     * @return 逆転送履歴レスポンス
     */
    @GetMapping("/friend-forward-exports")
    @Operation(summary = "自チーム投稿の逆転送履歴取得",
            description = "自チーム発信投稿がどのフレンドチームに転送されたかを追跡する透明性API。"
                    + "非公開フレンドの名前は「匿名チーム」で返却する。")
    public ResponseEntity<FriendForwardExportListResponse> listExportedPosts(
            @PathVariable("id") Long teamId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long userId = SecurityUtils.getCurrentUserId();

        int effectivePage = Math.max(page, 0);
        int effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Pageable pageable = PageRequest.of(effectivePage, effectiveSize,
                Sort.by(Sort.Direction.DESC, "forwardedAt"));
        FriendForwardExportListResponse response = forwardService
                .listExportedPosts(teamId, pageable, userId);
        return ResponseEntity.ok(response);
    }
}
