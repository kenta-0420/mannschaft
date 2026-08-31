package com.mannschaft.app.market.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.market.dto.MarketCategoryDto;
import com.mannschaft.app.market.dto.MarketListingResponse;
import com.mannschaft.app.market.dto.MarketOwnerDto;
import com.mannschaft.app.market.dto.MarketRegionDto;
import com.mannschaft.app.market.dto.MarketRegionNodeResponse;
import com.mannschaft.app.market.dto.MarketSummaryResponse;
import com.mannschaft.app.market.service.MarketQueryService;
import com.mannschaft.app.matching.service.RegionTranslationService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.RecruitmentCategoryResponse;
import com.mannschaft.app.recruitment.service.RecruitmentCategoryService;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link MarketController} の MockMvc 結合テスト（F22.1 市 / 02_api_design §3・§04_security §1.3）。
 *
 * <ul>
 *   <li>200: 未ログインで公開札一覧・詳細・地域・件数に到達できる</li>
 *   <li>404: 非公開 / 不在の札（MARKET_404 → 存在秘匿）</li>
 *   <li>PII 禁則: 公開 DTO に個人情報フィールドが漏洩しない（CI 必須）</li>
 * </ul>
 */
@WebMvcTest(MarketController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MarketController 結合テスト (F22.1 市)")
class MarketControllerTest {

    /** 公開 DTO に<strong>絶対に</strong>含まれてはならないフィールド名（§04_security §1.3）。 */
    static final String[] FORBIDDEN_FIELDS = {
            "email", "emails", "phone", "phoneNumber",
            "lastName", "firstName", "birthday", "birthDate",
            "address", "addressLine", "streetAddress",
            // 応募者個人情報・連絡先
            "applicants", "applicantList", "participants", "participantList",
            "createdByName", "contact", "contactInfo"
    };

    private static final Long LISTING_ID = 1234L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketQueryService marketQueryService;

    @MockitoBean
    private RegionTranslationService regionTranslationService;

    @MockitoBean
    private RecruitmentCategoryService recruitmentCategoryService;

    // WebMvcTest が要求する Security / Proxy 周りの最小モック注入。
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        // 言語正規化は実 Service と同じ振る舞い（対応言語→そのまま / ja・未対応→null）をモックで再現。
        given(regionTranslationService.normalizeLang(any())).willAnswer(inv -> {
            String raw = inv.getArgument(0);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String base = raw.trim().toLowerCase();
            for (char sep : new char[]{',', ';', '-', '_'}) {
                int idx = base.indexOf(sep);
                if (idx >= 0) {
                    base = base.substring(0, idx);
                }
            }
            return java.util.Set.of("en", "zh", "ko", "es", "de").contains(base) ? base : null;
        });
    }

    // ════════════════════════════════════════════════════════════
    // 200: 正常系（未ログイン到達）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /public/market/listings 200: 未ログインで一覧に到達し PII 抑制 DTO が返る")
    void listListings_anonymous_returns200() throws Exception {
        given(marketQueryService.searchListings(
                isNull(), isNull(), isNull(), isNull(), isNull(), anyBoolean(), any(), any(), isNull()))
                .willReturn(new PageImpl<>(List.of(sampleListing()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/public/market/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(LISTING_ID))
                .andExpect(jsonPath("$.data[0].title").value("11/3 練習試合の相手募集"))
                .andExpect(jsonPath("$.data[0].owner.displayName").value("別府FC"))
                .andExpect(jsonPath("$.data[0].confirmedCount").value(0))
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    @DisplayName("GET /public/market/listings: owner_type を検索サービスへ渡す")
    void listListings_ownerType_passesFilter() throws Exception {
        given(marketQueryService.searchListings(
                isNull(), isNull(), isNull(), eq(RecruitmentScopeType.PERSONAL), isNull(),
                anyBoolean(), any(), any(), isNull()))
                .willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/public/market/listings")
                        .param("owner_type", "PERSONAL"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /public/market/listings/{id} 200: 公開札詳細に到達")
    void getListing_public_returns200() throws Exception {
        given(marketQueryService.getListing(eq(LISTING_ID), any(), isNull())).willReturn(sampleListing());

        mockMvc.perform(get("/api/v1/public/market/listings/{id}", LISTING_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(LISTING_ID))
                .andExpect(jsonPath("$.data.region.prefectureName").value("大分県"));
    }

    @Test
    @DisplayName("GET /public/market/listings/{id} 200: 複数地域 regions[] が camelCase で返る（F22.1 Phase2 D）")
    void getListing_multiRegion_returnsRegionsCamelCase() throws Exception {
        given(marketQueryService.getListing(eq(LISTING_ID), any(), isNull()))
                .willReturn(multiRegionListing());

        mockMvc.perform(get("/api/v1/public/market/listings/{id}", LISTING_ID))
                .andExpect(status().isOk())
                // 後方互換: 単一 region は先頭（代表）
                .andExpect(jsonPath("$.data.region.prefectureCode").value("13"))
                // regions[] 全件（camelCase）
                .andExpect(jsonPath("$.data.regions[0].prefectureCode").value("13"))
                .andExpect(jsonPath("$.data.regions[0].prefectureName").value("東京都"))
                .andExpect(jsonPath("$.data.regions[1].prefectureCode").value("14"))
                .andExpect(jsonPath("$.data.regions[1].cityCode").value("14100"))
                .andExpect(jsonPath("$.data.regions[1].cityName").value("横浜市"));
    }

    @Test
    @DisplayName("GET /public/market/regions 200: 都道府県一覧に到達")
    void getRegions_returns200() throws Exception {
        given(marketQueryService.getRegions(isNull(), isNull()))
                .willReturn(List.of(new MarketRegionNodeResponse("44", "大分県", null)));

        mockMvc.perform(get("/api/v1/public/market/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("44"))
                .andExpect(jsonPath("$.data[0].name").value("大分県"));
    }

    @Test
    @DisplayName("GET /public/market/summary 200: 地域別件数に到達")
    void getSummary_returns200() throws Exception {
        given(marketQueryService.getSummary(isNull())).willReturn(new MarketSummaryResponse(
                List.of(new MarketSummaryResponse.RegionCount("44", "大分県", 18L)),
                List.of(new MarketSummaryResponse.RegionCount("44202", "別府市", 7L))));

        mockMvc.perform(get("/api/v1/public/market/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byPrefecture[0].count").value(18))
                .andExpect(jsonPath("$.data.byCity[0].count").value(7));
    }

    @Test
    @DisplayName("GET /public/market/categories 200: 未ログインでジャンルマスタに到達し camelCase で返る")
    void getCategories_anonymous_returns200() throws Exception {
        // 市一覧ページのジャンルフィルタが認証必須 API を直叩きして 401 → /login へ飛ばされていた
        // 重大バグの根治。permitAll の公開エンドポイントで固定カテゴリマスタを返すことを検証する。
        given(recruitmentCategoryService.listCategories()).willReturn(List.of(
                new RecruitmentCategoryResponse(
                        7L, "PRACTICE_MATCH", "recruitment.category.practiceMatch",
                        "pi-flag", "TEAM", 1, true)));

        mockMvc.perform(get("/api/v1/public/market/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].code").value("PRACTICE_MATCH"))
                // snake_case ではなく camelCase で返ること（FE が型をそのまま使えること）
                .andExpect(jsonPath("$.data[0].nameI18nKey").value("recruitment.category.practiceMatch"))
                .andExpect(jsonPath("$.data[0].displayOrder").value(1))
                .andExpect(jsonPath("$.data[0].isActive").value(true));
    }

    // ════════════════════════════════════════════════════════════
    // 404: 非公開 / 不在の札（MARKET_404 → 存在秘匿）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /public/market/listings/{id} 404: 非公開 / 不在は MARKET_404")
    void getListing_notPublic_returns404() throws Exception {
        willThrow(new BusinessException(MarketErrorCode.LISTING_NOT_FOUND))
                .given(marketQueryService).getListing(eq(LISTING_ID), any(), isNull());

        mockMvc.perform(get("/api/v1/public/market/listings/{id}", LISTING_ID))
                .andExpect(status().isNotFound());
    }

    // ════════════════════════════════════════════════════════════
    // PII 禁則フィールド検出（CI 必須・最重要）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("公開 DTO に禁則ワードが漏洩していないこと（個人名 / 連絡先 / 応募者）")
    void marketListingResponse_doesNotLeakSensitiveFields() throws Exception {
        given(marketQueryService.getListing(eq(LISTING_ID), any(), isNull())).willReturn(sampleListing());

        MvcResult result = mockMvc.perform(get("/api/v1/public/market/listings/{id}", LISTING_ID))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(json)
                    .as("公開 DTO に禁則ワード '%s' が含まれてはならない（PII 漏洩防止）", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    // ────────────────────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────────────────────

    private MarketListingResponse sampleListing() {
        return new MarketListingResponse(
                LISTING_ID,
                "11/3 練習試合の相手募集",
                new MarketCategoryDto(7L, "recruitment.category.practiceMatch"),
                new MarketOwnerDto("TEAM", 88L, "別府FC", "https://cdn/icon.png"),
                new MarketRegionDto("44", "大分県", "44202", "別府市"),
                List.of(new MarketRegionDto("44", "大分県", "44202", "別府市")),
                "別府市総合運動公園",
                LocalDateTime.of(2026, 11, 3, 9, 0),
                LocalDateTime.of(2026, 11, 1, 23, 59),
                1,
                0,
                "OPEN",
                false,
                "INDIVIDUAL");
    }

    private MarketListingResponse multiRegionListing() {
        return new MarketListingResponse(
                LISTING_ID,
                "首都圏で練習試合の相手募集",
                new MarketCategoryDto(7L, "recruitment.category.practiceMatch"),
                new MarketOwnerDto("TEAM", 88L, "別府FC", "https://cdn/icon.png"),
                // 代表（先頭）= 東京都
                new MarketRegionDto("13", "東京都", null, null),
                // 全地域: 東京都（県単位）＋ 神奈川県横浜市
                List.of(
                        new MarketRegionDto("13", "東京都", null, null),
                        new MarketRegionDto("14", "神奈川県", "14100", "横浜市")),
                "首都圏各地",
                LocalDateTime.of(2026, 11, 3, 9, 0),
                LocalDateTime.of(2026, 11, 1, 23, 59),
                4,
                0,
                "OPEN",
                false,
                "INDIVIDUAL");
    }
}
