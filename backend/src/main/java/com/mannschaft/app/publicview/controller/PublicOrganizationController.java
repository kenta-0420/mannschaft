package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.publicview.dto.PublicOrganizationResponse;
import com.mannschaft.app.publicview.service.PublicOrganizationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F19.1 公開組織ページ Controller。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1 / §7.3</p>
 *
 * <p>このコントローラは<strong>認証不要</strong>（permitAll）であり、
 * PR-3 の SecurityConfig 整理で {@code /api/v1/public/organizations/*} が
 * permitAll パターンに含まれている前提で動作する。</p>
 *
 * <p>レート制限は publicview の {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter}
 * が担う（PR-3 で T15.4 PublicTeamApiRateLimitFilter からリネーム済）。</p>
 *
 * <p><strong>IDOR / エニュメレーション対策</strong>: PRIVATE / archived / 削除済 / 不在は
 * 一律 {@link com.mannschaft.app.publicview.error.PublicViewErrorCode#PUBLIC_001} (404) を返し
 * 状態を区別しない。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:274 — requestMatchers(GET, "/api/v1/public/organizations/*").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F19.1 公開組織ページ。<b>公開設定された組織のみ</b>を抑制版 DTO で返し、不在／非公開／削除済みは一律
 * 404 で状態を区別しない（IDOR・エニュメレーション対策）。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@RequestMapping("/api/v1/public/organizations")
@Tag(name = "公開組織ページ API (F19.1)")
@RequiredArgsConstructor
public class PublicOrganizationController {

    private final PublicOrganizationQueryService publicOrganizationQueryService;

    /**
     * 組織詳細を未ログインで取得する。
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "組織詳細（未ログイン公開）",
            description = "未ログインでも実行可能。PUBLIC かつ未 archive かつ未削除の組織のみ 200。"
                    + " それ以外は 404（IDOR 対策で状態を区別しない）。")
    public PublicOrganizationResponse getPublicOrganization(@PathVariable Long id) {
        return publicOrganizationQueryService.getPublicOrganization(id);
    }
}
