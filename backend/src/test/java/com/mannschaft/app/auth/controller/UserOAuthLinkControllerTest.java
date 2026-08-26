package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.service.AuthOAuthLinkService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UserOAuthLinkController} の結合テスト（試練 / red）。
 * <p>
 * {@code /settings/linked-accounts} の Google OAuth 連携ボタンが叩く
 * 認可URL生成エンドポイントに対する受け入れ条件 AC-1〜AC-4 を検証する。
 * <p>
 * <b>現時点ではエンドポイント未実装のため 404 となり、すべて RED になるのが正しい。</b>
 * 出陣（実装）フェーズで {@code GET /api/v1/users/me/oauth/link/{provider}/auth-url} を
 * 追加し、本テストを green 化する。
 */
@WebMvcTest(UserOAuthLinkController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserOAuthLinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthOAuthLinkService authOAuthLinkService;

    // ── @WebMvcTest コンテキストの依存解決用（AuthLoginControllerTest と同セット）──
    /** JwtAuthenticationFilter の依存解決用。 */
    @MockitoBean
    private AuthTokenService authTokenService;
    /** F11.3: UserLocaleFilter の依存解決用。 */
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    /** F14.1: ProxyInputContextFilter の依存解決用。 */
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    /** @EnableMethodSecurity 有効化後の SpEL ガード依存解決用。 */
    @MockitoBean
    private AccessGuard accessGuard;

    /** テスト用ユーザーID（SecurityContextHolder に設定する）。 */
    private static final Long TEST_USER_ID = 1L;

    /**
     * 認証済みユーザー（userId=1）を SecurityContextHolder に設定する。
     * {@code addFilters = false} でフィルターが無効なため、手動で設定する必要がある。
     */
    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(TEST_USER_ID), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /**
     * テスト後に SecurityContextHolder をクリアする。
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────
    // AC-1: GET /{provider}/auth-url 正常系 → 200 + { data: { authUrl } }
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-1: GET /GOOGLE/auth-url — 正常系: 200 で認可URL（$.data.authUrl）を返す"
            + "（UserOAuthLinkController#getAuthUrl — 連携対象は認証主体の userId のみ）")
    void getAuthUrl_google_success_returns200WithAuthUrl() throws Exception {
        // Given: サービスが Google の認可URLを返す
        given(authOAuthLinkService.generateAuthUrl(any(), eq("GOOGLE"), anyBoolean()))
                .willReturn("https://accounts.google.com/o/oauth2/v2/auth?client_id=test-client-id"
                        + "&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback&scope=openid&state=abc123");

        // When / Then: エンドポイント未実装のため現状は 404 → RED
        mockMvc.perform(get("/api/v1/users/me/oauth/link/GOOGLE/auth-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authUrl").exists());
    }

    // ──────────────────────────────────────────────
    // AC-2: 認証なし → 401
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-2: GET /GOOGLE/auth-url — 未認証: 401 を返す")
    void getAuthUrl_unauthenticated_returns401() throws Exception {
        // 未認証アクセスでは SecurityUtils.getCurrentUserId() が COMMON_000（→401）を投げる想定。
        // 現状はエンドポイント未実装のため 404 → RED。
        // addFilters=false でも、エンドポイント実装後に未認証時 401 を返す契約を検証する。
        given(authOAuthLinkService.generateAuthUrl(any(), eq("GOOGLE"), anyBoolean()))
                .willThrow(new BusinessException(
                        com.mannschaft.app.common.CommonErrorCode.COMMON_000));

        mockMvc.perform(get("/api/v1/users/me/oauth/link/GOOGLE/auth-url"))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────
    // AC-3: 既に連携済み → 409
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-3: GET /GOOGLE/auth-url — 既連携: 409（Conflict）を返す")
    void getAuthUrl_alreadyLinked_returns409() throws Exception {
        // Given: サービスが「既に連携済み」を表す 409 マッピングのエラーを投げる（AUTH_034 → CONFLICT）
        given(authOAuthLinkService.generateAuthUrl(any(), eq("GOOGLE"), anyBoolean()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_034));

        // When / Then: エンドポイント未実装のため現状は 404 → RED
        mockMvc.perform(get("/api/v1/users/me/oauth/link/GOOGLE/auth-url"))
                .andExpect(status().isConflict());
    }

    // ──────────────────────────────────────────────
    // AC-4: 不明プロバイダ → 400
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-4: GET /GITHUB/auth-url — 未サポートプロバイダ: 400（Bad Request）を返す")
    void getAuthUrl_unknownProvider_returns400() throws Exception {
        // Given: サポート外プロバイダで AUTH_028（→ デフォルト 400）が投げられる想定
        given(authOAuthLinkService.generateAuthUrl(any(), eq("GITHUB"), anyBoolean()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_028));

        // When / Then: エンドポイント未実装のため現状は 404 → RED
        mockMvc.perform(get("/api/v1/users/me/oauth/link/GITHUB/auth-url"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────────────────────────
    // 認可根治戦役 Wave5 ロットB — 自己スコープ契約テスト
    // UserOAuthLinkController#getCalendarOnlyAuthUrl
    //
    // 連携先ユーザーは SecurityContextHolder に設定した TEST_USER_ID のみで解決される
    // （厳密一致スタブが応答することで、他ユーザーIDが紛れ込む余地が無いことを固定する）。
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /GOOGLE/calendar-only-auth-url — 連携対象は認証主体の userId のみ"
            + "（UserOAuthLinkController#getCalendarOnlyAuthUrl）")
    void getCalendarOnlyAuthUrl_targetsOnlyAuthenticatedUser() throws Exception {
        given(authOAuthLinkService.generateCalendarOnlyAuthUrl(eq(TEST_USER_ID)))
                .willReturn("https://accounts.google.com/o/oauth2/v2/auth?scope=calendar&state=xyz");

        mockMvc.perform(get("/api/v1/users/me/oauth/link/GOOGLE/calendar-only-auth-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authUrl").exists());
    }
}
