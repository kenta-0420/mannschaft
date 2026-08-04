package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.publicview.dto.PublicEventResponse;
import com.mannschaft.app.publicview.service.PublicEventQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F19.1 Phase 7 公開イベント Controller。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.2 Phase 7</p>
 *
 * <p>このコントローラは<strong>認証不要</strong>（permitAll）。
 * チームの {@code public_events_enabled} フラグ、または組織の {@code public_events_enabled} フラグが
 * {@code true} の場合のみイベント一覧を返す。
 * フラグが {@code false} または PRIVATE の場合は 404 で隠蔽する（IDOR 対策）。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 2 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig — requestMatchers(GET, "/api/v1/public/teams/&#42;/events"
 * / "/api/v1/public/organizations/&#42;/events").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F19.1 公開ページのイベント一覧。<b>公開設定されたスコープの公開イベントのみ</b>を返す。未ログイン訪問者への告知が目的の公開ページ機能。
 * レート制限あり。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic({
        "/api/v1/public/teams/*/events",
        "/api/v1/public/organizations/*/events"
})
@RestController
@Tag(name = "公開イベント API (F19.1 Phase 7)")
@RequiredArgsConstructor
public class PublicEventController {

    /** 1 ページあたりの最大件数（深いページネーション抑止）。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 1 ページあたりのデフォルト件数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PublicEventQueryService publicEventQueryService;

    /**
     * チームの公開イベント一覧を取得する。
     *
     * @param teamId 対象チーム ID
     * @param page   ページ番号（0 始まり）
     * @param size   1 ページあたりの件数（最大 {@value MAX_PAGE_SIZE}）
     */
    @GetMapping("/api/v1/public/teams/{teamId}/events")
    @Operation(
            summary = "チームの公開イベント一覧（未ログイン公開）",
            description = "PUBLIC チームで public_events_enabled=true の場合のみ PUBLIC 可視性の PUBLISHED イベント一覧を返す。"
                    + " フラグが false / PRIVATE チームの場合は 404（IDOR 対策で隠蔽）。")
    public Page<PublicEventResponse> listTeamEvents(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = buildPageable(page, size);
        return publicEventQueryService.getTeamEvents(teamId, pageable);
    }

    /**
     * 組織の公開イベント一覧を取得する。
     *
     * @param orgId 対象組織 ID
     * @param page  ページ番号（0 始まり）
     * @param size  1 ページあたりの件数（最大 {@value MAX_PAGE_SIZE}）
     */
    @GetMapping("/api/v1/public/organizations/{orgId}/events")
    @Operation(
            summary = "組織の公開イベント一覧（未ログイン公開）",
            description = "PUBLIC 組織で public_events_enabled=true の場合のみ PUBLIC 可視性の PUBLISHED イベント一覧を返す。"
                    + " フラグが false / PRIVATE 組織の場合は 404（IDOR 対策で隠蔽）。")
    public Page<PublicEventResponse> listOrganizationEvents(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = buildPageable(page, size);
        return publicEventQueryService.getOrganizationEvents(orgId, pageable);
    }

    /**
     * ページネーションパラメータを正規化して Pageable を構築する。
     *
     * <p>page は 0 以上、size は 1〜{@value MAX_PAGE_SIZE} の範囲に収める。</p>
     */
    private Pageable buildPageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize);
    }
}
