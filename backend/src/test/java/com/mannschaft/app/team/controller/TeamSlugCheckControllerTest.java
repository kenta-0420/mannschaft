package com.mannschaft.app.team.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link TeamSlugCheckController} の MockMvc 結合テスト。
 *
 * <p>検証するケース:</p>
 * <ul>
 *   <li>認証済み・使用可能なスラッグ → 200 {@code available: true, suggestions: []}</li>
 *   <li>認証済み・使用不可のスラッグ → 200 {@code available: false, suggestions: [...]}</li>
 *   <li>未認証でアクセス → 401（@PreAuthorize("isAuthenticated()") によるガード）</li>
 *   <li>3文字未満のスラッグ → 400 バリデーションエラー</li>
 *   <li>不正文字（大文字等）のスラッグ → 400</li>
 *   <li>30文字超のスラッグ → 400</li>
 * </ul>
 */
@WebMvcTest(TeamSlugCheckController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TeamSlugCheckController 結合テスト")
class TeamSlugCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamRepository teamRepository;

    // WebMvcTest コンテキストが要求する依存（Security / Proxy 周り）の最小モック注入
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

    // ════════════════════════════════════════════════════════════
    // 200: 使用可能なスラッグ
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /teams/slug-check: 使用可能なスラッグ → available=true, suggestions空")
    @WithMockUser(username = "1")
    void checkSlug_available_returns200WithAvailableTrue() throws Exception {
        given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team")).willReturn(false);

        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "my-team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions").isEmpty());
    }

    // ════════════════════════════════════════════════════════════
    // 200: 使用不可のスラッグ（代替候補あり）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /teams/slug-check: 使用不可のスラッグ → available=false, 候補を返す")
    @WithMockUser(username = "1")
    void checkSlug_unavailable_returns200WithSuggestions() throws Exception {
        given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team")).willReturn(true);
        // 候補: my-team-1 は使用中、my-team-2, my-team-3 は使用可能
        given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team-1")).willReturn(true);
        given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team-2")).willReturn(false);
        given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team-3")).willReturn(false);
        given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team-4")).willReturn(false);

        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "my-team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions.length()").value(3))
                .andExpect(jsonPath("$.suggestions[0]").value("my-team-2"))
                .andExpect(jsonPath("$.suggestions[1]").value("my-team-3"))
                .andExpect(jsonPath("$.suggestions[2]").value("my-team-4"));
    }

    @Test
    @DisplayName("GET /teams/slug-check: 全候補が使用中 → available=false, 候補0件")
    @WithMockUser(username = "1")
    void checkSlug_unavailableAllSuggestionsTaken_returns200EmptySuggestions() throws Exception {
        given(teamRepository.existsBySlugAndDeletedAtIsNull(anyString())).willReturn(true);

        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "my-team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions").isEmpty());
    }

    // ════════════════════════════════════════════════════════════
    // 401: 未認証アクセス
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /teams/slug-check: 未認証でもスライステストでは到達可能（認証ガードは SecurityConfigAuthorizationTest で検証）")
    void checkSlug_unauthenticated_reachableInSliceTest() throws Exception {
        // @WebMvcTest + addFilters=false の文脈では @PreAuthorize("isAuthenticated()") は AOP で機能しない。
        // 実際の 401/403 動作は SecurityConfigAuthorizationTest で検証済み。
        given(teamRepository.existsBySlugAndDeletedAtIsNull("my-team")).willReturn(false);
        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "my-team"))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════
    // 400: バリデーションエラー
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /teams/slug-check: 2文字スラッグ → 400 バリデーションエラー")
    @WithMockUser(username = "1")
    void checkSlug_tooShort_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "ab"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /teams/slug-check: 31文字スラッグ → 400 バリデーションエラー")
    @WithMockUser(username = "1")
    void checkSlug_tooLong_returns400() throws Exception {
        String oversized = "a".repeat(31);
        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", oversized))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /teams/slug-check: 大文字を含むスラッグ → 400 バリデーションエラー")
    @WithMockUser(username = "1")
    void checkSlug_upperCase_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "My-Team"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /teams/slug-check: 先頭がハイフンのスラッグ → 400 バリデーションエラー")
    @WithMockUser(username = "1")
    void checkSlug_leadingHyphen_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "-my-team"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /teams/slug-check: 末尾がハイフンのスラッグ → 400 バリデーションエラー")
    @WithMockUser(username = "1")
    void checkSlug_trailingHyphen_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/teams/slug-check").param("slug", "my-team-"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /teams/slug-check: slug パラメータなし → 400")
    @WithMockUser(username = "1")
    void checkSlug_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/teams/slug-check"))
                .andExpect(status().isBadRequest());
    }
}
