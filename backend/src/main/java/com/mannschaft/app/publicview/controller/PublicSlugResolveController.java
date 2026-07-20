package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.dto.SlugResolveResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F01.2 §5.9.5: slug 解決（旧 slug → 新 slug 301 判定）公開 API。
 *
 * <p>公開ページ（チーム／組織）が、ブックマーク・被リンクされた<strong>旧 URL</strong> に
 * アクセスされた際、現行 slug へ 301 リダイレクトすべきかを判定するための <strong>認証不要</strong>
 * エンドポイント。SEO・ブックマーク保全のため未ログインでも到達可能にする。</p>
 *
 * <p>レスポンスは {@link SlugResolveResponse}（{@code status} と {@code canonicalSlug} のみ）。
 * 名前など実データは一切返さない（スコープ漏洩防止）。private チーム／組織の実データ取得は
 * {@code GET /api/v1/teams/{slug}} 等の認可が引き続き守る。slug → slug の対応自体は非機密として扱う。</p>
 *
 * <p>レート制限は {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter} が担う
 * （未ログイン 60 req/min/IP・ログイン 200 req/min/user。PUBLIC_API バケットを共有）。
 * パスセグメント {@code slug-resolve} は {@code SlugValidator} の予約語に登録済みのため、
 * {@code GET /api/v1/public/teams/{slug}} に食われない（slug として取得不可）。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 2 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:319-320 — requestMatchers(GET, "/api/v1/public/teams/slug-resolve"
 * / "/api/v1/public/organizations/slug-resolve").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F01.2 §5.9.5 slug 解決。応答は {@code status} と {@code canonicalSlug}
 * のみで<b>名前などの実データを一切返さない</b>（スコープ漏洩防止）。ブックマーク・被リンクされた旧 URL の301
 * 判定に未ログインで到達する必要がある。private の実データ取得は別途認可が守る。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@Tag(name = "slug 解決 公開 API (F01.2 §5.9.5)")
@RequiredArgsConstructor
public class PublicSlugResolveController {

    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * チーム slug を解決する（CURRENT / MOVED→canonicalSlug / NOT_FOUND）。
     *
     * @param slug 解決対象 slug
     * @return 解決結果
     */
    @GetMapping("/api/v1/public/teams/slug-resolve")
    @Operation(
            summary = "チーム slug 解決（未ログイン公開・301 判定）",
            description = "現行 slug なら CURRENT、旧 slug なら MOVED（canonicalSlug=現行 slug）、"
                    + "該当なしは NOT_FOUND。canonicalSlug 以外の実データは返さない。")
    public SlugResolveResponse resolveTeamSlug(@RequestParam String slug) {
        return teamService.resolveSlug(slug);
    }

    /**
     * 組織 slug を解決する（CURRENT / MOVED→canonicalSlug / NOT_FOUND）。
     *
     * @param slug 解決対象 slug
     * @return 解決結果
     */
    @GetMapping("/api/v1/public/organizations/slug-resolve")
    @Operation(
            summary = "組織 slug 解決（未ログイン公開・301 判定）",
            description = "現行 slug なら CURRENT、旧 slug なら MOVED（canonicalSlug=現行 slug）、"
                    + "該当なしは NOT_FOUND。canonicalSlug 以外の実データは返さない。")
    public SlugResolveResponse resolveOrganizationSlug(@RequestParam String slug) {
        return organizationService.resolveSlug(slug);
    }
}
