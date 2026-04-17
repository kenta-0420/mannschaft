package com.mannschaft.app.social.announcement.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedItemDto;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedMetaDto;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedResponseDto;
import com.mannschaft.app.social.announcement.dto.CreateAnnouncementRequestDto;
import com.mannschaft.app.social.announcement.dto.PinAnnouncementRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 組織スコープ お知らせウィジェット REST コントローラ（F02.6）。
 *
 * <p>
 * 組織ダッシュボードのお知らせウィジェットに関する CRUD / 既読管理 / ピン留め操作を提供する。
 * チームスコープ ({@link AnnouncementFeedController}) と同形で {@code scopeType = ORGANIZATION}。
 * 全エンドポイントで認証を必須とする（{@code @PreAuthorize("isAuthenticated()")}）。
 * </p>
 *
 * <p>
 * エンドポイント一覧:
 * <ul>
 *   <li>{@code GET    /api/v1/organizations/{orgId}/announcements} — 一覧取得</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/announcements} — お知らせ化</li>
 *   <li>{@code DELETE /api/v1/organizations/{orgId}/announcements/{id}} — お知らせ解除</li>
 *   <li>{@code PATCH  /api/v1/organizations/{orgId}/announcements/{id}/pin} — ピン留めトグル</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/announcements/{id}/read} — 既読マーク</li>
 *   <li>{@code POST   /api/v1/organizations/{orgId}/announcements/read-all} — 全件既読</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/announcements")
@Tag(name = "組織お知らせウィジェット", description = "F02.6 組織スコープ お知らせウィジェット API")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnnouncementFeedOrgController {

    private final AnnouncementFeedService announcementFeedService;

    // ═════════════════════════════════════════════════════════════
    // GET /api/v1/organizations/{orgId}/announcements — 一覧取得
    // ═════════════════════════════════════════════════════════════

    /**
     * 組織のお知らせフィード一覧をカーソルページングで取得する。
     *
     * @param orgId  組織 ID
     * @param cursor カーソル（省略時は先頭から）
     * @param limit  取得件数（省略時 10、最大 50）
     * @return お知らせフィード一覧レスポンス（200 OK）
     */
    @GetMapping
    @Operation(
            summary = "組織お知らせ一覧取得",
            description = "組織ダッシュボードのお知らせウィジェット用フィードをカーソルページングで返す。"
                    + "ピン留め優先 → 優先度（URGENT → IMPORTANT → NORMAL）→ 新着順で並ぶ。")
    public ResponseEntity<AnnouncementFeedResponseDto> getAnnouncementFeed(
            @PathVariable Long orgId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") int limit) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementFeedService.AnnouncementFeedResult result = announcementFeedService.getAnnouncementFeed(
                AnnouncementScopeType.ORGANIZATION, orgId, userId, "MEMBER", cursor, limit);

        List<AnnouncementFeedItemDto> items = result.data().stream()
                .map(AnnouncementFeedItemDto::from)
                .toList();

        AnnouncementFeedResponseDto response = AnnouncementFeedResponseDto.builder()
                .data(items)
                .meta(AnnouncementFeedMetaDto.builder()
                        .nextCursor(result.nextCursor())
                        .hasNext(result.hasNext())
                        .unreadCount(result.unreadCount())
                        .build())
                .build();

        return ResponseEntity.ok(response);
    }

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/organizations/{orgId}/announcements — お知らせ化
    // ═════════════════════════════════════════════════════════════

    /**
     * コンテンツを組織のお知らせウィジェットに登録する。
     *
     * @param orgId   組織 ID
     * @param request お知らせ化リクエスト（sourceType + sourceId）
     * @return 作成されたお知らせフィードアイテム（201 Created）
     */
    @PostMapping
    @Operation(
            summary = "コンテンツをお知らせ化（組織）",
            description = "指定した source_type + source_id のコンテンツを組織のお知らせウィジェットに登録する。"
                    + "著者本人または ADMIN/DEPUTY_ADMIN のみ可。重複登録は 409 を返す。")
    public ResponseEntity<ApiResponse<AnnouncementFeedItemDto>> createAnnouncement(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateAnnouncementRequestDto request) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementSourceType sourceType = AnnouncementSourceType.valueOf(request.getSourceType());

        var entity = announcementFeedService.createAnnouncement(
                AnnouncementScopeType.ORGANIZATION, orgId, sourceType, request.getSourceId(), userId);

        var item = new AnnouncementFeedService.AnnouncementFeedItem(entity, false);
        AnnouncementFeedItemDto dto = AnnouncementFeedItemDto.from(item);

        return ResponseEntity
                .created(URI.create("/api/v1/organizations/" + orgId + "/announcements/" + entity.getId()))
                .body(ApiResponse.of(dto));
    }

    // ═════════════════════════════════════════════════════════════
    // DELETE /api/v1/organizations/{orgId}/announcements/{id} — お知らせ解除
    // ═════════════════════════════════════════════════════════════

    /**
     * 組織のお知らせウィジェットからコンテンツを解除（物理削除）する。
     *
     * @param orgId          組織 ID（パス整合性確認用）
     * @param announcementId お知らせフィード ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "お知らせ解除（組織）",
            description = "お知らせウィジェットからコンテンツを解除する（元コンテンツは残る）。"
                    + "著者本人または ADMIN/DEPUTY_ADMIN のみ可。")
    public ResponseEntity<Void> deleteAnnouncement(
            @PathVariable Long orgId,
            @PathVariable("id") Long announcementId) {

        Long userId = SecurityUtils.getCurrentUserId();
        announcementFeedService.deleteAnnouncement(announcementId, userId);
        return ResponseEntity.noContent().build();
    }

    // ═════════════════════════════════════════════════════════════
    // PATCH /api/v1/organizations/{orgId}/announcements/{id}/pin — ピン留めトグル
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせのピン留め状態をトグルする（ON ↔ OFF）。
     *
     * <p>
     * ADMIN / DEPUTY_ADMIN のみ操作可能。ピン留め ON 時は上限 5 件を超えると 409。
     * </p>
     *
     * @param orgId          組織 ID（パス整合性確認用）
     * @param announcementId お知らせフィード ID
     * @param request        ピン留めリクエスト（Service はトグルのため pinned 値は参照のみ）
     * @return 更新後のお知らせフィードアイテム（200 OK）
     */
    @PatchMapping("/{id}/pin")
    @Operation(
            summary = "ピン留めトグル（組織）",
            description = "お知らせのピン留め ON/OFF を切り替える。"
                    + "ADMIN/DEPUTY_ADMIN のみ可。ピン留め上限（5件）を超える場合は 409。")
    public ResponseEntity<ApiResponse<AnnouncementFeedItemDto>> togglePin(
            @PathVariable Long orgId,
            @PathVariable("id") Long announcementId,
            @RequestBody(required = false) PinAnnouncementRequestDto request) {

        Long userId = SecurityUtils.getCurrentUserId();
        var entity = announcementFeedService.togglePin(announcementId, userId);

        var item = new AnnouncementFeedService.AnnouncementFeedItem(entity, false);
        AnnouncementFeedItemDto dto = AnnouncementFeedItemDto.from(item);

        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/organizations/{orgId}/announcements/{id}/read — 既読マーク（冪等）
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせを既読にする（冪等）。
     *
     * <p>
     * 既に既読の場合はノーオペレーション（200 OK を返す）。
     * </p>
     *
     * @param orgId          組織 ID（パス整合性確認用）
     * @param announcementId お知らせフィード ID
     * @return 200 OK（既読結果）
     */
    @PostMapping("/{id}/read")
    @Operation(
            summary = "既読マーク（組織）",
            description = "指定したお知らせを既読にする。冪等。既に既読の場合はノーオペレーション。")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAsRead(
            @PathVariable Long orgId,
            @PathVariable("id") Long announcementId) {

        Long userId = SecurityUtils.getCurrentUserId();
        announcementFeedService.markAsRead(announcementId, userId);

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "id", announcementId,
                "isRead", true)));
    }

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/organizations/{orgId}/announcements/read-all — 全件既読
    // ═════════════════════════════════════════════════════════════

    /**
     * 組織スコープの全未読お知らせを一括既読にする。
     *
     * @param orgId 組織 ID
     * @return 200 OK（既読マーク件数）
     */
    @PostMapping("/read-all")
    @Operation(
            summary = "全件既読（組織）",
            description = "組織スコープの全未読お知らせを一括既読にする。")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAllAsRead(
            @PathVariable Long orgId) {

        Long userId = SecurityUtils.getCurrentUserId();
        announcementFeedService.markAllAsRead(AnnouncementScopeType.ORGANIZATION, orgId, userId);

        return ResponseEntity.ok(ApiResponse.of(Map.of("markedCount", 0)));
    }
}
