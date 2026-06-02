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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicOrganizationPostController} の MockMvc 結合テスト (F19.1 Phase 2)。
 *
 * <p>{@link PublicTeamPostControllerTest} の組織版（対称構造）。</p>
 */
@WebMvcTest(PublicOrganizationPostController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicOrganizationPostController 結合テスト (F19.1 Phase 2)")
class PublicOrganizationPostControllerTest {

    private static final Long ORG_ID = 200L;
    private static final Long POST_ID = 5678L;

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
        given(viewerContextBuilder.buildForOrganization(any(Authentication.class), any(Long.class)))
                .willReturn(ViewerContext.anonymous());
        given(viewerContextBuilder.buildForOrganization(eq(null), any(Long.class)))
                .willReturn(ViewerContext.anonymous());
    }

    @Test
    @DisplayName("GET /public/organizations/{id}/posts 200: PUBLIC 組織の公開投稿一覧")
    void listPublicPosts_returns200() throws Exception {
        Page<PublicPostSummary> page = new PageImpl<>(
                List.of(sampleSummary()), PageRequest.of(0, 20), 1);
        given(publicPostQueryService.listPublicPostsByOrganization(eq(ORG_ID), any(Pageable.class), any(ViewerContext.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/posts", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sourceType").value("BLOG_POST"))
                .andExpect(jsonPath("$.content[0].sourceId").value(POST_ID))
                .andExpect(jsonPath("$.content[0].author.displayLabel").value(AnonymousLabels.POSTER))
                .andExpect(jsonPath("$.content[0].scope.scopeType").value("ORGANIZATION"))
                .andExpect(jsonPath("$.content[0].scope.scopeId").value(ORG_ID));
    }

    @Test
    @DisplayName("GET /public/organizations/{id}/posts 404: PRIVATE 組織の試行")
    void listPublicPosts_privateOrg_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicPostQueryService)
                .listPublicPostsByOrganization(eq(ORG_ID), any(Pageable.class), any(ViewerContext.class));

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/posts", ORG_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/organizations/{id}/posts/{postId} 200: 投稿詳細")
    void getPublicPostDetail_returns200() throws Exception {
        given(publicPostQueryService.findPublicPostDetailByOrganization(eq(ORG_ID), eq(POST_ID), any(ViewerContext.class)))
                .willReturn(sampleDetail());

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/posts/{postId}", ORG_ID, POST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(POST_ID))
                .andExpect(jsonPath("$.bodyHtml").value("<p>本文</p>"));
    }

    @Test
    @DisplayName("GET /public/organizations/{id}/posts/{postId} 404: 投稿不在")
    void getPublicPostDetail_postNotFound_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_003))
                .given(publicPostQueryService)
                .findPublicPostDetailByOrganization(eq(ORG_ID), eq(POST_ID), any(ViewerContext.class));

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/posts/{postId}", ORG_ID, POST_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /public/organizations/{id}/posts/{postId} 404: PRIVATE 組織配下の試行")
    void getPublicPostDetail_privateOrg_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicPostQueryService)
                .findPublicPostDetailByOrganization(eq(ORG_ID), eq(POST_ID), any(ViewerContext.class));

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/posts/{postId}", ORG_ID, POST_ID))
                .andExpect(status().isNotFound());
    }

    private PublicPostSummary sampleSummary() {
        return new PublicPostSummary(
                "BLOG_POST",
                POST_ID,
                "組織からのお知らせ",
                "組織活動の概要...",
                new PublicAuthorIdentity(
                        AnonymousLabels.POSTER,
                        DisplayIdentity.ANONYMOUS_AVATAR_URL,
                        false,
                        true),
                PublicScopeRef.ofOrganization(ORG_ID, "サンプル組織"),
                OffsetDateTime.of(2026, 5, 18, 12, 0, 0, 0, ZoneOffset.UTC));
    }

    private PublicPostDetail sampleDetail() {
        return new PublicPostDetail(
                "BLOG_POST",
                POST_ID,
                "組織からのお知らせ",
                "<p>本文</p>",
                new PublicAuthorIdentity(
                        AnonymousLabels.POSTER,
                        DisplayIdentity.ANONYMOUS_AVATAR_URL,
                        false,
                        true),
                PublicScopeRef.ofOrganization(ORG_ID, "サンプル組織"),
                OffsetDateTime.of(2026, 5, 18, 12, 0, 0, 0, ZoneOffset.UTC));
    }
}
