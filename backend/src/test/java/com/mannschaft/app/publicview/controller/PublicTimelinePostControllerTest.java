package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.dto.PublicTimelinePostResponse;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.PublicTimelinePostQueryService;
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
 * {@link PublicTimelinePostController} の MockMvc 結合テスト (F19.1 Phase 7)。
 *
 * <p>テスト ID: TIMELINE-001〜006</p>
 *
 * <p>設計書 §6.2 Phase 7 のステータスコード網羅:</p>
 * <ul>
 *   <li>TIMELINE-001: 200 — timeline_posts_public=true のチームの投稿一覧取得成功</li>
 *   <li>TIMELINE-002: 404 — timeline_posts_public=false / PRIVATE チーム</li>
 *   <li>TIMELINE-003: 200 — 組織の公開タイムライン投稿一覧取得成功</li>
 *   <li>TIMELINE-004: 404 — timeline_posts_public=false / PRIVATE 組織</li>
 *   <li>TIMELINE-005: ページネーションパラメータが正しく適用されること</li>
 *   <li>TIMELINE-006: レスポンス JSON に PII 禁則ワードが含まれないこと</li>
 * </ul>
 */
@WebMvcTest(PublicTimelinePostController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicTimelinePostController 結合テスト (F19.1 Phase 7)")
class PublicTimelinePostControllerTest {

    /** レスポンスに含まれてはならない PII 禁則フィールド名。 */
    private static final String[] FORBIDDEN_FIELDS = {
            "userId", "authorId",
            "email", "phone", "firstName", "lastName",
            "lastNameKana", "firstNameKana",
            "passwordHash", "refreshToken",
            "authorRealNameSnapshot",
            "deletedAt", "status"
    };

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long POST_ID = 9001L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicTimelinePostQueryService publicTimelinePostQueryService;

    // WebMvcTest で起動される SecurityConfig 関連の Bean
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
    }

    /**
     * TIMELINE-001: timeline_posts_public=true のチームの投稿一覧取得成功。
     */
    @Test
    @DisplayName("TIMELINE-001: GET /public/teams/{id}/timeline-posts 200 — timeline_posts_public=true のチーム")
    void listTeamTimelinePosts_returns200() throws Exception {
        Page<PublicTimelinePostResponse> page = new PageImpl<>(
                List.of(sampleTeamPost()),
                PageRequest.of(0, 20),
                1);
        given(publicTimelinePostQueryService.getTeamTimelinePosts(eq(TEAM_ID), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/timeline-posts", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(POST_ID))
                .andExpect(jsonPath("$.content[0].content").value("秋の新メニューを始めました"))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeType").value("TEAM"))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeId").value(TEAM_ID))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * TIMELINE-002: timeline_posts_public=false / PRIVATE チームは 404。
     */
    @Test
    @DisplayName("TIMELINE-002: GET /public/teams/{id}/timeline-posts 404 — timeline_posts_public=false / PRIVATE チーム")
    void listTeamTimelinePosts_privateOrFlagOff_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicTimelinePostQueryService)
                .getTeamTimelinePosts(eq(TEAM_ID), any(Pageable.class));

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/timeline-posts", TEAM_ID))
                .andExpect(status().isNotFound());
    }

    /**
     * TIMELINE-003: 組織の公開タイムライン投稿一覧取得成功。
     */
    @Test
    @DisplayName("TIMELINE-003: GET /public/organizations/{id}/timeline-posts 200 — timeline_posts_public=true の組織")
    void listOrganizationTimelinePosts_returns200() throws Exception {
        Page<PublicTimelinePostResponse> page = new PageImpl<>(
                List.of(sampleOrgPost()),
                PageRequest.of(0, 20),
                1);
        given(publicTimelinePostQueryService.getOrganizationTimelinePosts(eq(ORG_ID), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/timeline-posts", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(POST_ID))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeType").value("ORGANIZATION"))
                .andExpect(jsonPath("$.content[0].scopeRef.scopeId").value(ORG_ID));
    }

    /**
     * TIMELINE-004: timeline_posts_public=false / PRIVATE 組織は 404。
     */
    @Test
    @DisplayName("TIMELINE-004: GET /public/organizations/{id}/timeline-posts 404 — timeline_posts_public=false / PRIVATE 組織")
    void listOrganizationTimelinePosts_privateOrFlagOff_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(publicTimelinePostQueryService)
                .getOrganizationTimelinePosts(eq(ORG_ID), any(Pageable.class));

        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/timeline-posts", ORG_ID))
                .andExpect(status().isNotFound());
    }

    /**
     * TIMELINE-005: ページネーションパラメータが正しく適用されること。
     */
    @Test
    @DisplayName("TIMELINE-005: GET /public/teams/{id}/timeline-posts ページネーション — page=1&size=5")
    void listTeamTimelinePosts_pagination_appliesParameters() throws Exception {
        Page<PublicTimelinePostResponse> emptyPage = new PageImpl<>(
                List.of(),
                PageRequest.of(1, 5),
                0);
        given(publicTimelinePostQueryService.getTeamTimelinePosts(eq(TEAM_ID), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/public/teams/{teamId}/timeline-posts", TEAM_ID)
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(5));
    }

    /**
     * TIMELINE-006: レスポンス JSON に PII 禁則ワードが含まれないこと。
     */
    @Test
    @DisplayName("TIMELINE-006: 公開タイムライン投稿レスポンス JSON に PII 禁則ワードが含まれないこと")
    void listTeamTimelinePosts_doesNotLeakSensitiveFields() throws Exception {
        Page<PublicTimelinePostResponse> page = new PageImpl<>(
                List.of(sampleTeamPost()),
                PageRequest.of(0, 20),
                1);
        given(publicTimelinePostQueryService.getTeamTimelinePosts(eq(TEAM_ID), any(Pageable.class)))
                .willReturn(page);

        String json = mockMvc.perform(get("/api/v1/public/teams/{teamId}/timeline-posts", TEAM_ID))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String forbidden : FORBIDDEN_FIELDS) {
            org.assertj.core.api.Assertions.assertThat(json)
                    .as("公開タイムライン投稿 JSON に禁則ワード '%s' が含まれてはならない", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    // ────────────────────────────────────────────────────────────
    // テストデータファクトリ
    // ────────────────────────────────────────────────────────────

    private PublicTimelinePostResponse sampleTeamPost() {
        return new PublicTimelinePostResponse(
                POST_ID,
                "秋の新メニューを始めました",
                PublicScopeRef.ofTeam(TEAM_ID, "サンプルチーム"),
                OffsetDateTime.of(2026, 5, 23, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    private PublicTimelinePostResponse sampleOrgPost() {
        return new PublicTimelinePostResponse(
                POST_ID,
                "組織からのお知らせです",
                PublicScopeRef.ofOrganization(ORG_ID, "サンプル組織"),
                OffsetDateTime.of(2026, 5, 23, 10, 0, 0, 0, ZoneOffset.UTC));
    }
}
