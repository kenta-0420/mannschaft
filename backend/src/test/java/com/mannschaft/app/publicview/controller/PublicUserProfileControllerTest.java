package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.PublicUserPostSummaryResponse;
import com.mannschaft.app.publicview.dto.PublicUserProfileResponse;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.PublicUserProfileQueryService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * {@link PublicUserProfileController} の MockMvc 結合テスト（F19.1 Phase 6）。
 *
 * <p>設計書 §6.6 のステータスコード網羅:</p>
 * <ul>
 *   <li>PROF_001: public_profile_enabled=true のユーザーのプロフィール取得 → 200</li>
 *   <li>PROF_002: プロフィール非公開ユーザー → 404（PUBLIC_007）</li>
 *   <li>PROF_003: 存在しない userId → 404</li>
 *   <li>PROF_004: 削除済みユーザー → 404</li>
 *   <li>PROF_005: 公開投稿一覧取得成功 → 200、件数正しい</li>
 *   <li>PROF_006: 公開投稿一覧（public_visible=false の投稿は除外される）</li>
 *   <li>PROF_007: 非公開ユーザーの投稿一覧 → 404</li>
 * </ul>
 */
@WebMvcTest(PublicUserProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicUserProfileController 結合テスト (F19.1 Phase 6)")
class PublicUserProfileControllerTest {

    /** PII / 内部状態に該当する禁則フィールド名（CI で漏洩検出）。 */
    static final String[] FORBIDDEN_FIELDS = {
            "email", "emails", "phone", "phoneNumber", "phones",
            "firstName", "lastName", "lastNameKana", "firstNameKana",
            "birthday", "passwordHash", "refreshToken",
            "addressLine", "streetAddress",
            "status", "archivedAt", "deletedAt", "version",
            "publicProfileEnabled", "locale", "timezone",
            "gender", "genderHash", "prefectureCode", "cityCode"
    };

    private static final Long USER_ID = 101L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicUserProfileQueryService publicUserProfileQueryService;

    // WebMvcTest が要求する依存の最小モック注入
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
    }

    @Test
    @DisplayName("PROF_001: GET /public/users/{id} 200 — public_profile_enabled=true で抑制 DTO が返る")
    void getProfile_publicUser_returns200() throws Exception {
        given(publicUserProfileQueryService.getPublicProfile(eq(USER_ID)))
                .willReturn(sampleProfileResponse());

        mockMvc.perform(get("/api/v1/public/users/{userId}", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.displayName").value("テストユーザー"))
                .andExpect(jsonPath("$.avatarUrl").value("https://cdn/avatar.png"))
                .andExpect(jsonPath("$.memberSince").value("2023-04-01"));
    }

    @Test
    @DisplayName("PROF_002: GET /public/users/{id} 404 — プロフィール非公開ユーザー（PUBLIC_007 → 404）")
    void getProfile_privateUser_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_007))
                .given(publicUserProfileQueryService).getPublicProfile(eq(USER_ID));

        mockMvc.perform(get("/api/v1/public/users/{userId}", USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PROF_003: GET /public/users/{id} 404 — 存在しない userId")
    void getProfile_nonExistentUser_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_007))
                .given(publicUserProfileQueryService).getPublicProfile(eq(999L));

        mockMvc.perform(get("/api/v1/public/users/{userId}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PROF_004: GET /public/users/{id} 404 — 削除済みユーザー（IDOR 対策で一律 404）")
    void getProfile_deletedUser_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_007))
                .given(publicUserProfileQueryService).getPublicProfile(eq(USER_ID));

        mockMvc.perform(get("/api/v1/public/users/{userId}", USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PROF_005: GET /public/users/{id}/posts 200 — 公開投稿一覧取得成功・件数正しい")
    void getPosts_publicUser_returns200WithCorrectCount() throws Exception {
        Page<PublicUserPostSummaryResponse> page = new PageImpl<>(
                List.of(samplePostSummary(1L, "投稿1"), samplePostSummary(2L, "投稿2")),
                PageRequest.of(0, 20),
                2
        );
        given(publicUserProfileQueryService.getPublicPosts(eq(USER_ID), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/users/{userId}/posts", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].postId").value(1L))
                .andExpect(jsonPath("$.content[0].title").value("投稿1"))
                .andExpect(jsonPath("$.content[0].scopeType").value("TEAM"))
                .andExpect(jsonPath("$.content[1].postId").value(2L));
    }

    @Test
    @DisplayName("PROF_006: GET /public/users/{id}/posts 200 — public_visible=false 投稿は除外済み（サービス層で保証）")
    void getPosts_publicVisibleFalsePostsExcluded_returnsOnlyPublicVisible() throws Exception {
        // public_visible=false の投稿はサービス層のクエリで除外されるため、
        // ここではサービスが 1 件だけ返す状況をシミュレートする
        Page<PublicUserPostSummaryResponse> page = new PageImpl<>(
                List.of(samplePostSummary(10L, "公開投稿のみ")),
                PageRequest.of(0, 20),
                1
        );
        given(publicUserProfileQueryService.getPublicPosts(eq(USER_ID), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/users/{userId}/posts", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("PROF_007: GET /public/users/{id}/posts 404 — 非公開ユーザーの投稿一覧（IDOR 対策で一律 404）")
    void getPosts_privateUser_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_007))
                .given(publicUserProfileQueryService).getPublicPosts(eq(USER_ID), any(Pageable.class));

        mockMvc.perform(get("/api/v1/public/users/{userId}/posts", USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未ログインでもプロフィール Controller に到達できる")
    void getProfile_anonymous_canReachController() throws Exception {
        SecurityContextHolder.clearContext();
        given(publicUserProfileQueryService.getPublicProfile(eq(USER_ID)))
                .willReturn(sampleProfileResponse());

        mockMvc.perform(get("/api/v1/public/users/{userId}", USER_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("抑制 DTO に禁則ワードが漏洩していないこと（PII / 内部状態）")
    void publicUserProfileResponse_doesNotLeakSensitiveFields() throws Exception {
        given(publicUserProfileQueryService.getPublicProfile(eq(USER_ID)))
                .willReturn(sampleProfileResponse());

        MvcResult result = mockMvc.perform(get("/api/v1/public/users/{userId}", USER_ID))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        for (String forbidden : FORBIDDEN_FIELDS) {
            assertThat(json)
                    .as("公開ユーザー DTO に禁則ワード '%s' が含まれてはならない", forbidden)
                    .doesNotContain("\"" + forbidden + "\"");
        }
    }

    private PublicUserProfileResponse sampleProfileResponse() {
        return new PublicUserProfileResponse(
                USER_ID,
                "テストユーザー",
                "https://cdn/avatar.png",
                LocalDate.of(2023, 4, 1)
        );
    }

    private PublicUserPostSummaryResponse samplePostSummary(Long postId, String title) {
        return new PublicUserPostSummaryResponse(
                postId,
                title,
                "TEAM",
                "テストチーム",
                "200",
                LocalDateTime.of(2024, 1, 15, 10, 0)
        );
    }
}
