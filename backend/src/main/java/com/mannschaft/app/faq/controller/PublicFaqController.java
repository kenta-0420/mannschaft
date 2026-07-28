package com.mannschaft.app.faq.controller;

import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.faq.dto.PublicFaqResponse;
import com.mannschaft.app.faq.service.PublicFaqQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F21.1 §5.5.6: 公開FAQ Controller。
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5.6 / §5.5.7</p>
 *
 * <p>このコントローラは<strong>認証不要</strong>（permitAll）。
 * {@code GET /api/v1/public/teams/{teamId}/faqs} /
 * {@code GET /api/v1/public/organizations/{orgId}/faqs} を提供し、
 * 回答済み FAQ（固定質問 → 自由質問 順）を返す。FE はこれを公開ページ表示と
 * FAQPage JSON-LD 構築のソースとして用いる。</p>
 *
 * <p><strong>IDOR / エニュメレーション対策</strong>: PRIVATE / archived / 削除済 / 不在は
 * {@link com.mannschaft.app.publicview.service.PublicFaqQueryService} 内の公開可否判定により
 * 一律 {@link com.mannschaft.app.publicview.error.PublicViewErrorCode#PUBLIC_001}（404）へ正規化され、
 * 状態を区別しない。</p>
 *
 * <p>SecurityConfig での permitAll 登録（GET 2 パス）・
 * {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter} のレート制限対象登録は
 * 本フェーズで追加済み。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 2 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:310-311 — requestMatchers(GET, "/api/v1/public/teams/&#42;/faqs"
 * / "/api/v1/public/organizations/&#42;/faqs").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F21.1 §5.5 公開 FAQ。<b>回答済みの FAQ のみ</b>を返す公開情報で、運営者が公開を意図して掲載したコンテンツに限られる。
 * レート制限あり。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公開FAQ API (F21.1 §5.5)")
@RequiredArgsConstructor
public class PublicFaqController {

    private final PublicFaqQueryService publicFaqQueryService;

    /**
     * 公開チームのFAQ一覧を取得する。
     *
     * @param teamId 対象チーム ID
     * @return 回答済みFAQ（固定質問 displayOrder 昇順 → 自由質問 displayOrder 昇順）
     */
    @GetMapping("/teams/{teamId}/faqs")
    @Operation(
            summary = "チームの公開FAQ（未ログイン公開）",
            description = "PUBLIC チームの回答済み FAQ を返す。固定質問（questionKey 非null・"
                    + "FE が i18n で質問文描画）を先頭に、続けて自由質問（questionText を保持）を返す。"
                    + " PRIVATE チームの ID で叩いた場合は 404（IDOR 対策で隠蔽）。")
    public List<PublicFaqResponse> getTeamFaqs(@PathVariable Long teamId) {
        return publicFaqQueryService.getPublicTeamFaqs(teamId);
    }

    /**
     * 公開組織のFAQ一覧を取得する。
     *
     * @param orgId 対象組織 ID
     * @return 回答済みFAQ（固定質問 displayOrder 昇順 → 自由質問 displayOrder 昇順）
     */
    @GetMapping("/organizations/{orgId}/faqs")
    @Operation(
            summary = "組織の公開FAQ（未ログイン公開）",
            description = "PUBLIC 組織の回答済み FAQ を返す。固定質問（questionKey 非null・"
                    + "FE が i18n で質問文描画）を先頭に、続けて自由質問（questionText を保持）を返す。"
                    + " PRIVATE 組織の ID で叩いた場合は 404（IDOR 対策で隠蔽）。")
    public List<PublicFaqResponse> getOrganizationFaqs(@PathVariable Long orgId) {
        return publicFaqQueryService.getPublicOrganizationFaqs(orgId);
    }
}
