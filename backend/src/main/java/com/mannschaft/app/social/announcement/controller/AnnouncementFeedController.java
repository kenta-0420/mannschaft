package com.mannschaft.app.social.announcement.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import com.mannschaft.app.social.announcement.AnnouncementReadService;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedItemDto;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedMetaDto;
import com.mannschaft.app.social.announcement.dto.AnnouncementFeedResponseDto;
import com.mannschaft.app.social.announcement.dto.AnnouncementMarkAllReadResultDto;
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
 * チームスコープ お知らせウィジェット REST コントローラ（F02.6）。
 *
 * <p>
 * チームダッシュボードのお知らせウィジェットに関する CRUD / 既読管理 / ピン留め操作を提供する。
 * 全エンドポイントで認証を必須とする（{@code @PreAuthorize("isAuthenticated()")}）。
 * </p>
 *
 * <p>
 * エンドポイント一覧:
 * <ul>
 *   <li>{@code GET    /api/v1/teams/{teamId}/announcements} — 一覧取得</li>
 *   <li>{@code POST   /api/v1/teams/{teamId}/announcements} — お知らせ化</li>
 *   <li>{@code DELETE /api/v1/teams/{teamId}/announcements/{id}} — お知らせ解除</li>
 *   <li>{@code PATCH  /api/v1/teams/{teamId}/announcements/{id}/pin} — ピン留めトグル</li>
 *   <li>{@code POST   /api/v1/teams/{teamId}/announcements/{id}/read} — 既読マーク</li>
 *   <li>{@code POST   /api/v1/teams/{teamId}/announcements/read-all} — 全件既読</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{teamId}/announcements")
@Tag(name = "チームお知らせウィジェット", description = "F02.6 チームスコープ お知らせウィジェット API")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnnouncementFeedController {

    private final AnnouncementFeedService announcementFeedService;
    private final RoleResolver roleResolver;

    // ═════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/announcements — 一覧取得
    // ═════════════════════════════════════════════════════════════

    /**
     * チームのお知らせフィード一覧をカーソルページングで取得する。
     *
     * @param teamId    チーム ID
     * @param cursor    カーソル（省略時は先頭から）
     * @param limit     取得件数（省略時 10、最大 50）
     * @return お知らせフィード一覧レスポンス（200 OK）
     */
    @GetMapping
    @Operation(
            summary = "チームお知らせ一覧取得",
            description = "チームダッシュボードのお知らせウィジェット用フィードをカーソルページングで返す。"
                    + "ピン留め優先 → 優先度（URGENT → IMPORTANT → NORMAL）→ 新着順で並ぶ。")
    public ResponseEntity<AnnouncementFeedResponseDto> getAnnouncementFeed(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") int limit) {

        Long userId = SecurityUtils.getCurrentUserId();
        // 固定 "MEMBER" ではなく、当該チームに対する閲覧者の実ロールを解決して渡す（可視性漏洩根治）。
        ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, "TEAM", teamId);
        AnnouncementFeedService.AnnouncementFeedResult result = announcementFeedService.getAnnouncementFeed(
                AnnouncementScopeType.TEAM, teamId, userId, viewerRole.name(), cursor, limit);

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
    // POST /api/v1/teams/{teamId}/announcements — お知らせ化
    // ═════════════════════════════════════════════════════════════

    /**
     * コンテンツをチームのお知らせウィジェットに登録する。
     *
     * @param teamId  チーム ID
     * @param request お知らせ化リクエスト（sourceType + sourceId）
     * @return 作成されたお知らせフィードアイテム（201 Created）
     */
    @PostMapping
    @Operation(
            summary = "コンテンツをお知らせ化",
            description = "指定した source_type + source_id のコンテンツをチームのお知らせウィジェットに登録する。"
                    + "著者本人または ADMIN/DEPUTY_ADMIN のみ可。重複登録は 409 を返す。")
    public ResponseEntity<ApiResponse<AnnouncementFeedItemDto>> createAnnouncement(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateAnnouncementRequestDto request) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementSourceType sourceType = AnnouncementSourceType.valueOf(request.getSourceType());

        var entity = announcementFeedService.createAnnouncement(
                AnnouncementScopeType.TEAM, teamId, sourceType, request.getSourceId(), userId);

        // 作成直後は isRead = false
        var item = new AnnouncementFeedService.AnnouncementFeedItem(entity, false);
        AnnouncementFeedItemDto dto = AnnouncementFeedItemDto.from(item);

        return ResponseEntity
                .created(URI.create("/api/v1/teams/" + teamId + "/announcements/" + entity.getId()))
                .body(ApiResponse.of(dto));
    }

    // ═════════════════════════════════════════════════════════════
    // DELETE /api/v1/teams/{teamId}/announcements/{id} — お知らせ解除
    // ═════════════════════════════════════════════════════════════

    /**
     * チームのお知らせウィジェットからコンテンツを解除（物理削除）する。
     *
     * @param teamId         チーム ID（パス整合性確認用）
     * @param announcementId お知らせフィード ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "お知らせ解除",
            description = "お知らせウィジェットからコンテンツを解除する（元コンテンツは残る）。"
                    + "著者本人または ADMIN/DEPUTY_ADMIN のみ可。")
    public ResponseEntity<Void> deleteAnnouncement(
            @PathVariable Long teamId,
            @PathVariable("id") Long announcementId) {

        Long userId = SecurityUtils.getCurrentUserId();
        announcementFeedService.deleteAnnouncement(announcementId, userId);
        return ResponseEntity.noContent().build();
    }

    // ═════════════════════════════════════════════════════════════
    // PATCH /api/v1/teams/{teamId}/announcements/{id}/pin — ピン留めトグル
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせのピン留め状態をトグルする（ON ↔ OFF）。
     *
     * <p>
     * ADMIN / DEPUTY_ADMIN のみ操作可能。ピン留め ON 時は上限 5 件を超えると 409。
     * </p>
     *
     * @param teamId         チーム ID（パス整合性確認用）
     * @param announcementId お知らせフィード ID
     * @param request        ピン留めリクエスト（Service はトグルのため pinned 値は参照のみ）
     * @return 更新後のお知らせフィードアイテム（200 OK）
     */
    @PatchMapping("/{id}/pin")
    @Operation(
            summary = "ピン留めトグル",
            description = "お知らせのピン留め ON/OFF を切り替える。"
                    + "ADMIN/DEPUTY_ADMIN のみ可。ピン留め上限（5件）を超える場合は 409。")
    public ResponseEntity<ApiResponse<AnnouncementFeedItemDto>> togglePin(
            @PathVariable Long teamId,
            @PathVariable("id") Long announcementId,
            @RequestBody(required = false) PinAnnouncementRequestDto request) {

        Long userId = SecurityUtils.getCurrentUserId();
        var entity = announcementFeedService.togglePin(announcementId, userId);

        // togglePin 後は既読状態が変わらないため isRead = false として返す（ピン操作の結果のみ必要）
        var item = new AnnouncementFeedService.AnnouncementFeedItem(entity, false);
        AnnouncementFeedItemDto dto = AnnouncementFeedItemDto.from(item);

        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/announcements/{id}/read — 既読マーク（冪等）
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせを既読にする（冪等）。
     *
     * <p>
     * 既に既読の場合はノーオペレーション（200 OK を返す）。
     * </p>
     *
     * <p>
     * <b>認可は可視性ベース（「見える＝既読にできる」・設計書 F02.6 §6.2.1）</b>。
     * {@code teamId} は Service まで通し、(1) {@code announcementId} が当該チームに帰属すること、
     * (2) そのお知らせが<b>当該チームの一覧でその閲覧者に見えること</b>の検証に用いる。
     * 在籍（メンバーシップ）では判定しない — 一覧は非メンバーにも {@code PUBLIC} を返すため、
     * 在籍で絞ると「見えているのに既読にできない」不整合が生じる。逆に在籍だけを見ると
     * 応援者が一覧に出ない内輪限定を既読化できてしまう。
     * </p>
     *
     * @param teamId         チーム ID（スコープ帰属検証・可視性検証に使用。捨ててはならない）
     * @param announcementId お知らせフィード ID
     * @return 200 OK（既読結果）
     */
    @PostMapping("/{id}/read")
    @Operation(
            summary = "既読マーク",
            description = "指定したお知らせを既読にする。冪等。既に既読の場合はノーオペレーション。")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAsRead(
            @PathVariable Long teamId,
            @PathVariable("id") Long announcementId) {

        Long userId = SecurityUtils.getCurrentUserId();
        announcementFeedService.markAsRead(AnnouncementScopeType.TEAM, teamId, announcementId, userId);

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "id", announcementId,
                "isRead", true)));
    }

    // ═════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/announcements/read-all — 全件既読
    // ═════════════════════════════════════════════════════════════

    /**
     * チームスコープの全未読お知らせを一括既読にする。
     *
     * <p><b>応答（#2530 ①）</b>: {@code markedCount} は<b>実際に既読化した件数</b>である
     * （以前はハードコードの {@code 0} を返しており、FE は「未読 0」と表示しつつ
     * 実際には未読が残ることがあった）。1 リクエストの防御上限
     * （{@code 500 × 20 = 10,000 件}）で打ち切った場合は {@code hasMoreUnread=true} を返し、
     * 「まだ残っている・もう一度実行すれば続きが処理される」ことを利用者に伝えられるようにする。</p>
     *
     * @param teamId チーム ID
     * @return 200 OK（既読マーク件数と残余の有無）
     */
    @PostMapping("/read-all")
    @Operation(
            summary = "全件既読",
            description = "チームスコープの全未読お知らせを一括既読にする。"
                    + "1 リクエストの上限（500 件 × 20 チャンク）で打ち切った場合は hasMoreUnread=true を返す。")
    public ResponseEntity<ApiResponse<AnnouncementMarkAllReadResultDto>> markAllAsRead(
            @PathVariable Long teamId) {

        Long userId = SecurityUtils.getCurrentUserId();
        AnnouncementReadService.MarkAllReadOutcome outcome =
                announcementFeedService.markAllAsRead(AnnouncementScopeType.TEAM, teamId, userId);

        return ResponseEntity.ok(ApiResponse.of(AnnouncementMarkAllReadResultDto.builder()
                .markedCount(outcome.markedCount())
                .hasMoreUnread(outcome.hasMoreUnread())
                .build()));
    }
}
