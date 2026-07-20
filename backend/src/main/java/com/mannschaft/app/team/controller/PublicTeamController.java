package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.team.dto.TeamPublicDetailResponse;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F15.4 Phase 5-α: 店舗詳細ページの未ログイン公開 API。
 *
 * <p>設計書: {@code docs/features/F15.4_phase5_team_public_detail.md} §4
 *
 * <p>このコントローラは <strong>認証不要</strong>（permitAll）であり、
 * 既存 {@link TeamController#getTeam(Long)}（{@code GET /api/v1/teams/{id}}、認証必須）
 * とは独立した分離型エンドポイントとして提供する。
 *
 * <p>レート制限は {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter}
 * が担う（未ログイン 60 req/min/IP・ログイン 200 req/min/user）。
 * 旧名: {@code com.mannschaft.app.team.filter.PublicTeamApiRateLimitFilter}（F19.1 Phase 1 でリネーム）。
 *
 * <p>権限分岐:
 * <ul>
 *   <li>PUBLIC チーム → 抑制版 DTO {@link TeamPublicDetailResponse} を返却</li>
 *   <li>不在 / 削除済 / archived / visibility != PUBLIC → 一律 404
 *       （IDOR / エニュメレーション対策）</li>
 * </ul>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:268 — requestMatchers(GET, "/api/v1/public/teams/*").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F15.4 Phase 5-α 店舗詳細の未ログイン公開 API。<b>visibility=PUBLIC のチームのみ</b>
 * 抑制版 DTO を返し、不在／削除済／archived／PUBLIC 以外は一律 404 で状態を区別しない（IDOR・エニュメレーション対策）
 * 。認証必須の {@code GET /api/v1/teams/*} とは分離されている。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@RequestMapping("/api/v1/public/teams")
@Tag(name = "店舗詳細 公開 API (F15.4)")
@RequiredArgsConstructor
public class PublicTeamController {

    private final TeamService teamService;

    /**
     * 店舗詳細を未ログインで取得する。
     *
     * @param id チーム ID
     * @return 抑制版チーム詳細レスポンス
     */
    @GetMapping("/{slug}")
    @Operation(
            summary = "店舗詳細（未ログイン公開）",
            description = "未ログインでも実行可能。PUBLIC かつ未 archive かつ未削除のチームのみ 200。"
                    + " それ以外は 404（IDOR 対策で状態を区別しない）。")
    public TeamPublicDetailResponse getPublicTeam(@PathVariable String slug) {
        Long id = teamService.resolveTeamId(slug);
        return teamService.getPublicTeam(id);
    }
}
