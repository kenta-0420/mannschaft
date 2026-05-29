package com.mannschaft.app.faq.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.faq.FaqCategory;
import com.mannschaft.app.faq.FixedFaqQuestion;
import com.mannschaft.app.faq.ScopeType;
import com.mannschaft.app.faq.dto.FaqEditorResponse;
import com.mannschaft.app.faq.error.FaqErrorCode;
import com.mannschaft.app.faq.service.FaqAdminService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminFaqController} の MockMvc 結合テスト（F21.1 §5.5）。
 *
 * <p><b>認可テストの実装方針（重要）</b>: 本コードベースの {@code SecurityConfig} は
 * {@code @EnableWebSecurity} のみで {@code @EnableMethodSecurity} を有効化していない
 * （開発中は Method Security 素通し。HTTP 層は {@code /api/v1/admin/**} を
 * {@code anyRequest().authenticated()} でガードする）。
 * したがって {@code @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")} は
 * 現時点では MockMvc 上で 403 を発火させられない。これは
 * {@link com.mannschaft.app.actionmemo.admin.AdminActionMemoController} のテストと同じ事情である。
 * そこで本テストでは以下の二段構えで認可を担保する:</p>
 * <ul>
 *   <li><b>403 相当（非管理者の拒否）</b>: 各ハンドラに
 *       {@code @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")} が
 *       <em>宣言されていること</em>を Reflection で検証する。
 *       {@code @EnableMethodSecurity} 有効化時に Spring Security が自動的に 403 を返す。</li>
 *   <li><b>401 相当（未認証の拒否）</b>: HTTP 層の {@code anyRequest().authenticated()} が
 *       {@code /api/v1/admin/**} を保護することを {@link com.mannschaft.app.config.SecurityConfig}
 *       の構成として担保（{@code SecurityConfigAuthorizationTest} が deny-by-default を別途検証済み）。
 *       本 MockMvc テストは {@code addFilters = false} のため認証フィルタを通さず、
 *       ハンドラ本体の入出力（200 / 204 / 400 / 404）を検証する。</li>
 *   <li><b>200（管理者アクセス可）</b>: 認証済み principal を投入し、ハンドラが
 *       想定どおりの応答を返すことを検証する。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5.6</p>
 */
@WebMvcTest(AdminFaqController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AdminFaqController 結合テスト (F21.1 §5.5)")
class AdminFaqControllerTest {

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long OPERATOR_USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FaqAdminService faqAdminService;

    /** @WebMvcTest コンテキスト用: JwtAuthenticationFilter 依存解決 */
    @MockitoBean
    private AuthTokenService authTokenService;

    /** @WebMvcTest コンテキスト用: UserLocaleFilter 依存解決 */
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    /** @WebMvcTest コンテキスト用: ProxyInputContextFilter 依存解決 */
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        // SecurityUtils.getCurrentUserId() は authentication.getName() を Long.valueOf する
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(OPERATOR_USER_ID), null, List.of()));
    }

    // ─────────────────────────────────────────────────────────────────
    // 認可（@PreAuthorize 宣言の存在確認）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("認可: @PreAuthorize('hasRole(ADMIN) or hasRole(SYSTEM_ADMIN)') が全ハンドラに付与されている")
    class AuthorizationTest {

        private static final String EXPECTED_EXPR = "hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')";

        @Test
        @DisplayName("getTeamFaqs / saveTeamFaqs / getOrganizationFaqs / saveOrganizationFaqs に @PreAuthorize が宣言されている")
        void allHandlersDeclarePreAuthorize() throws NoSuchMethodException {
            assertPreAuthorize("getTeamFaqs", Long.class);
            assertPreAuthorize("saveTeamFaqs", Long.class,
                    com.mannschaft.app.faq.dto.SaveFaqRequest.class);
            assertPreAuthorize("getOrganizationFaqs", Long.class);
            assertPreAuthorize("saveOrganizationFaqs", Long.class,
                    com.mannschaft.app.faq.dto.SaveFaqRequest.class);
        }

        private void assertPreAuthorize(String methodName, Class<?>... paramTypes)
                throws NoSuchMethodException {
            Method m = AdminFaqController.class.getMethod(methodName, paramTypes);
            PreAuthorize annotation = m.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s に @PreAuthorize が未付与だと非管理者が FAQ 管理 API を叩けてしまう", methodName)
                    .isNotNull();
            assertThat(annotation.value())
                    .as("%s は ADMIN / SYSTEM_ADMIN 以外を拒否する式でなければならない", methodName)
                    .isEqualTo(EXPECTED_EXPR);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GET: 編集画面用ペイロード（カテゴリ別固定6問）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET 編集ペイロード取得")
    class GetEditorPayload {

        @Test
        @DisplayName("GET /admin/teams/{id}/faqs 200: CLUB(=SPORTS) のチームは SPORTS 固定6問を返す")
        void getTeamFaqs_sports_returns200() throws Exception {
            given(faqAdminService.getEditorPayload(eq(ScopeType.TEAM), eq(TEAM_ID)))
                    .willReturn(editorPayload(FaqCategory.SPORTS));

            mockMvc.perform(get("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.category").value("SPORTS"))
                    .andExpect(jsonPath("$.fixedQuestions.length()").value(6))
                    .andExpect(jsonPath("$.fixedQuestions[0].questionKey")
                            .value(FixedFaqQuestion.SPORTS_ACTIVITY.name()));
        }

        @Test
        @DisplayName("GET /admin/organizations/{id}/faqs 200: HOSPITAL(=HEALTH) の組織は HEALTH 固定6問を返す")
        void getOrganizationFaqs_health_returns200() throws Exception {
            given(faqAdminService.getEditorPayload(eq(ScopeType.ORGANIZATION), eq(ORG_ID)))
                    .willReturn(editorPayload(FaqCategory.HEALTH));

            mockMvc.perform(get("/api/v1/admin/organizations/{orgId}/faqs", ORG_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.category").value("HEALTH"))
                    .andExpect(jsonPath("$.fixedQuestions.length()").value(6))
                    .andExpect(jsonPath("$.fixedQuestions[0].questionKey")
                            .value(FixedFaqQuestion.HEALTH_SERVICE.name()));
        }

        @Test
        @DisplayName("GET /admin/teams/{id}/faqs 404: 対象不在で FAQ_010")
        void getTeamFaqs_notFound_returns404() throws Exception {
            given(faqAdminService.getEditorPayload(eq(ScopeType.TEAM), eq(TEAM_ID)))
                    .willThrow(new BusinessException(FaqErrorCode.FAQ_010));

            mockMvc.perform(get("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAQ_010"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // PUT: 一括 upsert
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT 一括更新")
    class SaveFaqs {

        @Test
        @DisplayName("PUT /admin/teams/{id}/faqs 204: 正常保存")
        void saveTeamFaqs_valid_returns204() throws Exception {
            willDoNothing().given(faqAdminService)
                    .save(eq(ScopeType.TEAM), eq(TEAM_ID), any(), eq(OPERATOR_USER_ID));

            mockMvc.perform(put("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "fixedAnswers": [
                                        { "questionKey": "SPORTS_ACTIVITY", "answer": "サッカーをしています" }
                                      ],
                                      "customFaqs": [
                                        { "questionText": "駐車場はありますか", "answer": "あります", "displayOrder": 0 }
                                      ]
                                    }
                                    """))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("PUT 400: 自由質問8件は Bean Validation(@Size max=7) で 400")
        void saveTeamFaqs_eightCustomFaqs_returns400() throws Exception {
            mockMvc.perform(put("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(eightCustomFaqsJson()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PUT 400: サービスが FAQ_001 を投げたら 400（件数上限の二重防御）")
        void saveTeamFaqs_serviceThrowsFaq001_returns400() throws Exception {
            willThrow(new BusinessException(FaqErrorCode.FAQ_001))
                    .given(faqAdminService)
                    .save(eq(ScopeType.TEAM), eq(TEAM_ID), any(), eq(OPERATOR_USER_ID));

            mockMvc.perform(put("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "customFaqs": [
                                        { "questionText": "Q", "answer": "A", "displayOrder": 0 }
                                      ]
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FAQ_001"));
        }

        @Test
        @DisplayName("PUT 400: カテゴリ外の固定質問キーで FAQ_002")
        void saveTeamFaqs_categoryMismatchKey_returns400() throws Exception {
            willThrow(new BusinessException(FaqErrorCode.FAQ_002))
                    .given(faqAdminService)
                    .save(eq(ScopeType.TEAM), eq(TEAM_ID), any(), eq(OPERATOR_USER_ID));

            mockMvc.perform(put("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "fixedAnswers": [
                                        { "questionKey": "HEALTH_SERVICE", "answer": "回答" }
                                      ]
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FAQ_002"));
        }

        @Test
        @DisplayName("PUT 400: answer 1001 文字は Bean Validation(@Size max=1000) で 400")
        void saveTeamFaqs_answer1001Chars_returns400() throws Exception {
            String longAnswer = "あ".repeat(1001);
            String json = """
                    {
                      "fixedAnswers": [
                        { "questionKey": "SPORTS_ACTIVITY", "answer": "%s" }
                      ]
                    }
                    """.formatted(longAnswer);

            mockMvc.perform(put("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PUT /admin/teams/{id}/faqs 404: 対象不在で FAQ_010")
        void saveTeamFaqs_notFound_returns404() throws Exception {
            willThrow(new BusinessException(FaqErrorCode.FAQ_010))
                    .given(faqAdminService)
                    .save(eq(ScopeType.TEAM), eq(TEAM_ID), any(), eq(OPERATOR_USER_ID));

            mockMvc.perform(put("/api/v1/admin/teams/{teamId}/faqs", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "fixedAnswers": [
                                        { "questionKey": "SPORTS_ACTIVITY", "answer": "回答" }
                                      ]
                                    }
                                    """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAQ_010"));
        }

        @Test
        @DisplayName("PUT /admin/organizations/{id}/faqs 204: 組織でも正常保存")
        void saveOrganizationFaqs_valid_returns204() throws Exception {
            willDoNothing().given(faqAdminService)
                    .save(eq(ScopeType.ORGANIZATION), eq(ORG_ID), any(), eq(OPERATOR_USER_ID));

            mockMvc.perform(put("/api/v1/admin/organizations/{orgId}/faqs", ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "fixedAnswers": [
                                        { "questionKey": "HEALTH_SERVICE", "answer": "内科・外科を診療しています" }
                                      ]
                                    }
                                    """))
                    .andExpect(status().isNoContent());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────────────────────────

    /**
     * 指定カテゴリの固定6問（全件・未回答 answer=null）と空の自由質問を持つ編集ペイロードを生成する。
     */
    private FaqEditorResponse editorPayload(FaqCategory category) {
        List<FaqEditorResponse.FixedFaqItem> fixed = new ArrayList<>();
        for (FixedFaqQuestion q : FixedFaqQuestion.ofCategory(category)) {
            fixed.add(FaqEditorResponse.FixedFaqItem.builder()
                    .questionKey(q.name())
                    .displayOrder(q.displayOrder())
                    .answer(null)
                    .build());
        }
        return FaqEditorResponse.builder()
                .category(category.name())
                .fixedQuestions(fixed)
                .customFaqs(List.of())
                .build();
    }

    /**
     * 自由質問 8 件（@Size(max=7) 違反）の JSON を生成する。
     */
    private String eightCustomFaqsJson() {
        StringBuilder sb = new StringBuilder("{ \"customFaqs\": [");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{ \"questionText\": \"Q").append(i)
                    .append("\", \"answer\": \"A").append(i)
                    .append("\", \"displayOrder\": ").append(i).append(" }");
        }
        sb.append("] }");
        return sb.toString();
    }
}
