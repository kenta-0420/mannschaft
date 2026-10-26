package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.PublicAuthorIdentity;
import com.mannschaft.app.publicview.dto.PublicPostDetail;
import com.mannschaft.app.publicview.dto.PublicPostSummary;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.PublicPostQueryService;
import com.mannschaft.app.publicview.service.ViewerContextBuilder;
import com.mannschaft.app.publicview.visibility.AnonymousLabels;
import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicTeamPostController} の MockMvc 結合テスト (F19.1 Phase 2)。
 *
 * <p>設計書 §6.1 / §4.6 のステータスコード網羅:</p>
 * <ul>
 *   <li>200: PUBLIC チームの公開投稿一覧 / 詳細（段階開示済み）</li>
 *   <li>404: PRIVATE チーム / 不在 / 非公開記事（IDOR 対策で一律 404）</li>
 *   <li>未ログインの場合は汎用ラベルが返ること（ANONYMOUS ViewerContext）</li>
 *   <li>レスポンス JSON に禁則ワードが含まれない（PII 漏洩防止）</li>
 * </ul>
 */
@WebMvcTest(PublicTeamPostController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicTeamPostController 結合テスト (F19.1 Phase 2)")
class PublicTeamPostControllerTest {

    static final String[] FORBIDDEN_FIELDS = {
            "userId", "authorId",
            "email", "phone", "firstName", "lastName",
            "lastNameKana", "firstNameKana", "birthday",
            "passwordHash", "refreshToken",
            "realNameSnapshot",
            "version", "archivedAt", "deletedAt",
            "rejectionReason", "previewToken"
    };

    private static final Long TEAM_ID = 100L;
    private static final Long POST_ID = 4567L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicPostQueryService publicPostQueryService;

    @MockitoBean
    private ViewerContextBuilder viewerContextBuilder;

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
    void setUp() {
        SecurityContextHolder.clearContext();
        // デフォルト: ViewerContextBuilder は ANONYMOUS ViewerContext を返す
        given(viewerContextBuilder.buildForTeam(any(Authentication.class), any(Long.class)))
                .willReturn(ViewerContext.anonymous());
        given(viewerContextBuilder.buildForTeam(eq(null), any(Long.class)))
                .willReturn(ViewerContext.anonymous());
    }

    @Test
    @DisplayName("GET /public/teams/{id}/posts 200: PUBLIC チームの公開投稿一覧（段階開示 = 汎用ラベル）")
    void listPublicPosts_returns200WithAnonymousIdentity() throws Exception {
        Page<PublicPostSummary> page = new PageImpl<>(
                List.of(sampleSummary()),
                PageRequest.of(0, 20),
                1);
        given(publicPostQueryService.listPublicPostsByTeam(eq(TEAM_ID), any(Pageable.class), any(ViewerContext.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/posts", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sourceType").value("BLOG_POST"))
                .andExpect(jsonPath("$.content[0].sourceId").value(POST_ID))
                .andExpect(jsonPath("$.content[0].title").value("新メニューのお知らせ"))
                // Phase 1: 未ログイン = 汎用ラベル「投稿者」+ 汎用アバター
                .andExpect(jsonPath("$.content[0].author.displayLabel").value(AnonymousLabels.POSTER))
                .andExpect(jsonPath("$.content[0].author.avatarUrl")
                        .value(DisplayIdentity.ANONYMOUS_AVATAR_URL))
                .andExpect(jsonPath("$.content[0].author.teamAffiliationVisible").value(false))
                .andExpect(jsonPath("$.content[0].author.isAnonymized").value(true))
                .andExpect(jsonPath("$.content[0].scope.scopeType").value("TEAM"))
                .andExpect(jsonPath("$.content[0].scope.scopeId").value(TEAM_ID));
    }

    @Test
    @DisplayName("GET /public/teams/{id}/posts 404: PRIVATE チームの試行")
    void listPublicPosts_privateTeam_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicPostQueryService)
                .listPublicPostsByTeam(eq(TEAM_ID), any(Pageable.class), any(ViewerContext.class));

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/posts", TEAM_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/teams/{id}/posts/{postId} 200: 投稿詳細（段階開示済み）")
    void getPublicPostDetail_returns200() throws Exception {
        given(publicPostQueryService.findPublicPostDetailByTeam(eq(TEAM_ID), eq(POST_ID), any(ViewerContext.class)))
                .willReturn(sampleDetail());

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/posts/{postId}", TEAM_ID, POST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(POST_ID))
                .andExpect(jsonPath("$.title").value("新メニューのお知らせ"))
                .andExpect(jsonPath("$.bodyHtml").value("<p>本文 HTML</p>"))
                .andExpect(jsonPath("$.author.displayLabel").value(AnonymousLabels.POSTER));
    }

    @Test
    @DisplayName("GET /public/teams/{id}/posts/{postId} 404: PUBLIC_003 投稿不在")
    void getPublicPostDetail_postNotFound_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_003))
                .given(publicPostQueryService).findPublicPostDetailByTeam(eq(TEAM_ID), eq(POST_ID), any(ViewerContext.class));

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/posts/{postId}", TEAM_ID, POST_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/teams/{id}/posts/{postId} 404: PRIVATE チーム配下の投稿試行")
    void getPublicPostDetail_privateTeam_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicPostQueryService).findPublicPostDetailByTeam(eq(TEAM_ID), eq(POST_ID), any(ViewerContext.class));

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/posts/{postId}", TEAM_ID, POST_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("公開投稿レスポンス JSON に PII 禁則ワードが含まれないこと")
    void publicPostResponse_doesNotLeakSensitiveFields() throws Exception {
        Page<PublicPostSummary> page = new PageImpl<>(
                List.of(sampleSummary()), PageRequest.of(0, 20), 1);
        given(publicPostQueryService.listPublicPostsByTeam(eq(TEAM_ID), any(Pageable.class), any(ViewerContext.class)))
                .willReturn(page);

        MvcResult result = mockMvc.perform(get("/api/v1/public/teams/{teamId}/posts", TEAM_ID))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(json)
                    .as("公開投稿一覧 JSON に禁則ワード '%s' が含まれてはならない", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    private PublicPostSummary sampleSummary() {
        return new PublicPostSummary(
                "BLOG_POST",
                POST_ID,
                "新メニューのお知らせ",
                "10月から秋の新メニューを開始します...",
                new PublicAuthorIdentity(
                        AnonymousLabels.POSTER,
                        DisplayIdentity.ANONYMOUS_AVATAR_URL,
                        false,
                        true),
                PublicScopeRef.ofTeam(TEAM_ID, "サンプルチーム"),
                OffsetDateTime.of(2026, 5, 18, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    private PublicPostDetail sampleDetail() {
        return new PublicPostDetail(
                "BLOG_POST",
                POST_ID,
                "新メニューのお知らせ",
                "<p>本文 HTML</p>",
                new PublicAuthorIdentity(
                        AnonymousLabels.POSTER,
                        DisplayIdentity.ANONYMOUS_AVATAR_URL,
                        false,
                        true),
                PublicScopeRef.ofTeam(TEAM_ID, "サンプルチーム"),
                OffsetDateTime.of(2026, 5, 18, 10, 0, 0, 0, ZoneOffset.UTC));
    }
}
