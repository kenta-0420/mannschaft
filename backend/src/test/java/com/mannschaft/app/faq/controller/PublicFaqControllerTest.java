package com.mannschaft.app.faq.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.faq.FixedFaqQuestion;
import com.mannschaft.app.faq.dto.PublicFaqResponse;
import com.mannschaft.app.faq.service.PublicFaqQueryService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicFaqController} の MockMvc 結合テスト（F21.1 §5.5.6）。
 *
 * <p>未認証公開（permitAll）API の入出力を検証する。{@code addFilters = false} のため
 * 認証フィルタは通さず、ハンドラ→{@link PublicFaqQueryService}（モック）の入出力に集中する。
 * permitAll が HTTP 層で効くことは {@code SecurityConfigAuthorizationTest} が別途担保する
 * （SecurityConfig:183-184 に GET 2 パスの permitAll を登録済み）。</p>
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>200: PUBLIC チーム/組織で回答済み FAQ を「固定→自由」順で返す</li>
 *   <li>回答済みのみ（未回答固定質問はサービスが除外済み = レスポンスに出ない）</li>
 *   <li>404: PRIVATE / 不在（PUBLIC_001 に正規化）</li>
 *   <li>200 + 空配列: 回答済み FAQ が 0 件の PUBLIC チーム</li>
 * </ul>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5.6 / §5.5.7</p>
 */
@WebMvcTest(PublicFaqController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicFaqController 結合テスト (F21.1 §5.5)")
class PublicFaqControllerTest {

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicFaqQueryService publicFaqQueryService;

    // @WebMvcTest が要求する依存の最小モック注入
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
        // 公開 API は未認証想定。SecurityContext を空にして「ログインしていない」状態を再現する。
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /public/teams/{id}/faqs 200: 回答済みFAQが「固定→自由」順で返る")
    void getTeamFaqs_public_returnsFixedThenCustomInOrder() throws Exception {
        // サービスは既に「回答済みのみ・固定(displayOrder昇順)→自由(displayOrder昇順)」に整列済みのリストを返す。
        List<PublicFaqResponse> ordered = List.of(
                // 固定（questionKey 非null・questionText null）
                new PublicFaqResponse(FixedFaqQuestion.SPORTS_ACTIVITY.name(), null, "サッカーをしています"),
                new PublicFaqResponse(FixedFaqQuestion.SPORTS_SCHEDULE.name(), null, "毎週土曜"),
                // 自由（questionKey null・questionText 非null）
                new PublicFaqResponse(null, "駐車場はありますか", "あります"));
        given(publicFaqQueryService.getPublicTeamFaqs(eq(TEAM_ID))).willReturn(ordered);

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/faqs", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                // 先頭2件は固定質問（questionKey 非null・questionText は null = JSONPath 上は空）
                .andExpect(jsonPath("$[0].questionKey").value(FixedFaqQuestion.SPORTS_ACTIVITY.name()))
                .andExpect(jsonPath("$[0].questionText").isEmpty())
                .andExpect(jsonPath("$[0].answer").value("サッカーをしています"))
                .andExpect(jsonPath("$[1].questionKey").value(FixedFaqQuestion.SPORTS_SCHEDULE.name()))
                // 末尾は自由質問（questionKey null = JSONPath 上は空・questionText 非null）
                .andExpect(jsonPath("$[2].questionKey").isEmpty())
                .andExpect(jsonPath("$[2].questionText").value("駐車場はありますか"))
                .andExpect(jsonPath("$[2].answer").value("あります"));
    }

    @Test
    @DisplayName("GET /public/teams/{id}/faqs 200: 回答済みのみ（未回答固定質問は出ない）")
    void getTeamFaqs_onlyAnswered() throws Exception {
        // SPORTS は固定6問あるが、回答済みは1問のみ（サービスが未回答を除外済み）。
        given(publicFaqQueryService.getPublicTeamFaqs(eq(TEAM_ID)))
                .willReturn(List.of(
                        new PublicFaqResponse(FixedFaqQuestion.SPORTS_COST.name(), null, "月3000円")));

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/faqs", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].questionKey").value(FixedFaqQuestion.SPORTS_COST.name()))
                .andExpect(jsonPath("$[0].answer").value("月3000円"));
    }

    @Test
    @DisplayName("GET /public/teams/{id}/faqs 404: PRIVATE / 不在は PUBLIC_001 に正規化")
    void getTeamFaqs_privateOrNotFound_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicFaqQueryService).getPublicTeamFaqs(eq(TEAM_ID));

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/faqs", TEAM_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PUBLIC_001"));
    }

    @Test
    @DisplayName("GET /public/teams/{id}/faqs 200: 回答済みFAQ 0件は空配列")
    void getTeamFaqs_noAnsweredFaqs_returnsEmptyArray() throws Exception {
        given(publicFaqQueryService.getPublicTeamFaqs(eq(TEAM_ID))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/faqs", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /public/organizations/{id}/faqs 200: 組織でも回答済みFAQを返す")
    void getOrganizationFaqs_public_returns200() throws Exception {
        given(publicFaqQueryService.getPublicOrganizationFaqs(eq(ORG_ID)))
                .willReturn(List.of(
                        new PublicFaqResponse(FixedFaqQuestion.HEALTH_SERVICE.name(), null, "内科を診療")));

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/faqs", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].questionKey").value(FixedFaqQuestion.HEALTH_SERVICE.name()))
                .andExpect(jsonPath("$[0].answer").value("内科を診療"));
    }

    @Test
    @DisplayName("GET /public/organizations/{id}/faqs 404: PRIVATE / 不在は PUBLIC_001 に正規化")
    void getOrganizationFaqs_privateOrNotFound_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicFaqQueryService).getPublicOrganizationFaqs(eq(ORG_ID));

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/faqs", ORG_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PUBLIC_001"));
    }
}
