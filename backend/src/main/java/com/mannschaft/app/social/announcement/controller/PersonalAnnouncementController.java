package com.mannschaft.app.social.announcement.controller;

import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 個人横断 お知らせウィジェット REST コントローラ（F02.6）。
 *
 * <p>
 * 個人ダッシュボードで所属する全チーム / 組織のお知らせを横断集約して返す API。
 * </p>
 *
 * <p>
 * エンドポイント一覧:
 * <ul>
 *   <li>{@code GET /api/v1/announcements/me} — 個人横断お知らせ一覧</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>TODO</b>: {@link com.mannschaft.app.social.announcement.AnnouncementFeedService#getPersonalFeed} が
 * 未実装のため、現在は空リストを返す暫定実装。実装時に以下を追加する:
 * <ol>
 *   <li>{@code AnnouncementFeedService} に {@code getPersonalFeed(userId, cursor, limit)} を実装</li>
 *   <li>フィールドに {@code AnnouncementFeedService announcementFeedService} を追加</li>
 *   <li>本メソッドから {@code announcementFeedService.getPersonalFeed(userId, cursor, limit)} を呼び出す</li>
 *   <li>{@code AnnouncementFeedResponseDto} を組み立ててレスポンスを返す</li>
 * </ol>
 * 詳細は設計書 F02.6 §4「GET /api/v1/announcements/me」を参照。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/announcements")
@Tag(name = "個人横断お知らせウィジェット", description = "F02.6 個人ダッシュボード用 横断お知らせ API")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalAnnouncementController {

    // TODO: AnnouncementFeedService.getPersonalFeed 実装後に注入する
    // private final AnnouncementFeedService announcementFeedService;

    // ═════════════════════════════════════════════════════════════
    // GET /api/v1/announcements/me — 個人横断お知らせ一覧
    // ═════════════════════════════════════════════════════════════

    /**
     * 個人ダッシュボード用の横断お知らせ一覧を取得する。
     *
     * <p>
     * 所属する全チーム / 組織の {@code announcement_feeds} を横断取得する。
     * {@code team_memberships.last_accessed_at} 上位 20 スコープに絞り込んでから
     * UNION（F02.2 の既存規則に合わせる）。
     * </p>
     *
     * <p>
     * <b>TODO</b>: {@code AnnouncementFeedService.getPersonalFeed} が未実装のため空リストを返す。
     * </p>
     *
     * @param limit 取得件数（省略時 15、最大 50）
     * @return 個人横断お知らせ一覧（200 OK。暫定: 空リスト）
     */
    @GetMapping("/me")
    @Operation(
            summary = "個人横断お知らせ一覧取得",
            description = "所属する全チーム/組織のお知らせを横断集約して返す（個人ダッシュボード用）。"
                    + "TODO: AnnouncementFeedService.getPersonalFeed 実装後に本実装に差し替える。")
    public ResponseEntity<Object> getPersonalFeed(
            @RequestParam(defaultValue = "15") int limit) {

        // getCurrentUserId を呼ぶことで認証確認のみ行う
        Long userId = SecurityUtils.getCurrentUserId();
        log.debug("個人横断お知らせ取得（暫定実装）userId={}, limit={}", userId, limit);

        // TODO: AnnouncementFeedService.getPersonalFeed(userId, cursor, limit) を呼ぶ
        // AnnouncementFeedService.AnnouncementFeedResult result =
        //     announcementFeedService.getPersonalFeed(userId, null, limit);
        // ...

        return ResponseEntity.ok(Collections.emptyList());
    }
}
