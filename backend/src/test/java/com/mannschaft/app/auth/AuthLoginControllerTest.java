package com.mannschaft.app.auth;

import com.mannschaft.app.auth.controller.AuthLoginController;
import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link AuthLoginController} の結合テスト。
 * {@code @WebMvcTest} でコントローラー層のみをロードし、Service は MockitoBean で差し替える。
 */
@WebMvcTest(AuthLoginController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthLoginControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/register
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /register — 正常系: 201 でメッセージを返却する")
    void register_success_returns201() throws Exception {
        var msgResp = MessageResponse.of("確認メールを送信しました");
        given(authService.register(any(RegisterRequest.class), anyString()))
                .willReturn(ApiResponse.of(msgResp));

        String body = """
                {
                  "email": "test@example.com",
                  "password": "Passw0rd!",
                  "lastName": "田中",
                  "firstName": "太郎",
                  "nickname": "taro",
                  "birth_date": "1990-01-01",
                  "privacyPolicyAccepted": true,
                  "privacyPolicyVersion": "1.1.0"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("確認メールを送信しました"));
    }

    @Test
    @DisplayName("POST /register — 異常系: バリデーション違反で 400 + fieldErrors を返却する")
    void register_validationError_returns400() throws Exception {
        // email, password, lastName, firstName すべて空
        String body = """
                {
                  "email": "",
                  "password": "",
                  "lastName": "",
                  "firstName": "",
                  "nickname": ""
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.fieldErrors").isArray());
    }

    @Test
    @DisplayName("POST /register — 回帰: nickname 欠落でも 500 ではなく 400（COMMON_001）を返す")
    void register_missingNickname_returns400NotServerError() throws Exception {
        // 実機 E2E で捕捉した新規登録 500 バグの回帰テスト。
        // nickname（= UserEntity.displayName、NOT NULL）を欠落させると、
        // 修正前は DB の NOT NULL 制約違反で 500（COMMON_999）になっていた。
        // RegisterRequest.nickname に @NotBlank を課したことで、
        // 正しく 400（COMMON_001）として弾かれることを検証する。
        String body = """
                {
                  "email": "no-nickname@example.com",
                  "password": "Passw0rd!",
                  "lastName": "山田",
                  "firstName": "太郎",
                  "postalCode": "123-4567",
                  "birth_date": "1990-01-01",
                  "locale": "ja",
                  "timezone": "Asia/Tokyo"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.fieldErrors[?(@.field == 'nickname')]").exists());
    }

    @Test
    @DisplayName("POST /register — 回帰: nickname 空文字でも 400（COMMON_001）を返す")
    void register_blankNickname_returns400() throws Exception {
        String body = """
                {
                  "email": "blank-nickname@example.com",
                  "password": "Passw0rd!",
                  "lastName": "山田",
                  "firstName": "太郎",
                  "nickname": "   ",
                  "postalCode": "123-4567",
                  "birth_date": "1990-01-01"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.fieldErrors[?(@.field == 'nickname')]").exists());
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/login
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /login — 正常系: 200 でトークンを返却する")
    void login_success_returns200() throws Exception {
        var loginResp = new LoginResponse(
                "access-token-xxx", "refresh-token-yyy", 3600L,
                1L, "taro", "test@example.com", null, false);
        given(authService.login(any(LoginRequest.class), any(), any()))
                .willAnswer(invocation -> ApiResponse.of(loginResp));

        String body = """
                {
                  "email": "test@example.com",
                  "password": "Passw0rd!",
                  "rememberMe": false
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "TestAgent")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.accessToken").value("access-token-xxx"))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    @DisplayName("POST /login — refresh_token を Set-Cookie（HttpOnly/SameSite=Strict）し body にも残す")
    void login_success_setsRefreshTokenCookie() throws Exception {
        var loginResp = new LoginResponse(
                "access-token-xxx", "refresh-token-yyy", 3600L,
                1L, "taro", "test@example.com", null, false);
        given(authService.login(any(LoginRequest.class), any(), any()))
                .willAnswer(invocation -> ApiResponse.of(loginResp));
        // refresh_token Cookie の maxAge に使用される
        given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);

        String body = """
                {
                  "email": "test@example.com",
                  "password": "Passw0rd!",
                  "rememberMe": false
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "TestAgent")
                        .content(body))
                .andExpect(status().isOk())
                // body にも refresh_token が残る（モバイル互換）
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-yyy"))
                // refresh_token Cookie が発行される（値・属性）
                .andExpect(cookie().value("refresh_token", "refresh-token-yyy"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().maxAge("refresh_token", 604800))
                .andExpect(header().stringValues(org.springframework.http.HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(containsString("refresh_token=refresh-token-yyy"))))
                .andExpect(header().stringValues(org.springframework.http.HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.allOf(
                                        containsString("refresh_token=refresh-token-yyy"),
                                        containsString("SameSite=Strict"),
                                        containsString("HttpOnly")))));
    }

    @Test
    @DisplayName("POST /login — 異常系: 認証失敗で 400（AUTH_001）を返却する")
    void login_authFailed_returns400() throws Exception {
        given(authService.login(any(LoginRequest.class), any(), any()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_001));

        String body = """
                {
                  "email": "test@example.com",
                  "password": "wrongpassword",
                  "rememberMe": false
                }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "TestAgent")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/verify-email
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /verify-email — 正常系: 200 でメッセージを返却する")
    void verifyEmail_success_returns200() throws Exception {
        var msgResp = MessageResponse.of("メール認証が完了しました");
        given(authService.verifyEmail(anyString()))
                .willReturn(ApiResponse.of(msgResp));

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"valid-token-123\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("メール認証が完了しました"));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/refresh
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /refresh — 正常系: 200 で新トークンを返却する")
    void refresh_success_returns200() throws Exception {
        var tokenResp = new TokenResponse("new-access-token", "new-refresh-token", 3600L);
        given(authService.refreshAccessToken(anyString(), any()))
                .willReturn(ApiResponse.of(tokenResp));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("POST /refresh — ローテーション後の refresh_token を Set-Cookie し body にも残す")
    void refresh_success_setsRefreshTokenCookie() throws Exception {
        var tokenResp = new TokenResponse("new-access-token", "new-refresh-token", 3600L);
        given(authService.refreshAccessToken(anyString(), any()))
                .willReturn(ApiResponse.of(tokenResp));
        given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "old-refresh-token")))
                .andExpect(status().isOk())
                // body にも refresh_token が残る（モバイル互換）
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"))
                // 新しい refresh_token Cookie が発行される
                .andExpect(cookie().value("refresh_token", "new-refresh-token"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().maxAge("refresh_token", 604800));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/refresh — 失敗経路の HTTP ステータス契約
    //
    // AUTH_007（リフレッシュトークン無効／リボーク済み）は「認証情報が無効」の意味論であり、
    // 401 Unauthorized を返さなければならない。AuthErrorCode の Severity.WARN 既定は 400 のため、
    // GlobalExceptionHandler.ERROR_CODE_STATUS_MAP での明示上書きが必要
    //（docs/security/06_business_logic_and_abuse_prevention.md §7.4 / §7.5）。
    //
    // AuthTokenRotationService の失敗経路（Cookie 欠落 / 不在トークン / 明示ログアウト済み /
    // 期限切れ）はすべて AUTH_007 に収束するため、Controller 層の契約としては
    // 「AUTH_007 が送出されたら 401」を経路ごとに固定する。
    // 各分岐が実際に AUTH_007 を投げること自体は AuthTokenRotationServiceTest が検証する。
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /refresh — Cookie 無し（null）: 401 + AUTH_007 を返す（NPE→500 根治）")
    void refresh_noCookie_returns401WithAuth007() throws Exception {
        // Given: Cookie が存在しない場合、AuthService は AUTH_007 の BusinessException を投げる
        given(authService.refreshAccessToken(any(), any()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_007));

        // When / Then: Cookie なしでアクセス → 401 + AUTH_007
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_007"));
    }

    @Test
    @DisplayName("POST /refresh — Cookie が空白のみ: 401 + AUTH_007 を返す")
    void refresh_blankCookie_returns401WithAuth007() throws Exception {
        given(authService.refreshAccessToken(any(), any()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_007));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "   ")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_007"));
    }

    @Test
    @DisplayName("POST /refresh — DB に存在しないトークン: 401 + AUTH_007 を返す")
    void refresh_unknownToken_returns401WithAuth007() throws Exception {
        given(authService.refreshAccessToken(anyString(), any()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_007));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "not-in-db")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_007"));
    }

    @Test
    @DisplayName("POST /refresh — 明示ログアウト済み（後継ポインタ無しの revoked）: 401 + AUTH_007 を返す")
    void refresh_revokedWithoutSuccessor_returns401WithAuth007() throws Exception {
        given(authService.refreshAccessToken(anyString(), any()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_007));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "revoked-by-logout")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_007"));
    }

    @Test
    @DisplayName("POST /refresh — 有効期限切れトークン: 401 + AUTH_007 を返す")
    void refresh_expiredToken_returns401WithAuth007() throws Exception {
        given(authService.refreshAccessToken(anyString(), any()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_007));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "expired-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_007"));
    }

    @Test
    @DisplayName("POST /refresh — 真リプレイ検出（AUTH_026）は従来どおり 401 のまま（挙動不変の確認）")
    void refresh_replayDetected_stillReturns401WithAuth026() throws Exception {
        given(authService.refreshAccessToken(anyString(), any()))
                .willThrow(new BusinessException(AuthErrorCode.AUTH_026));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "replayed-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_026"));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/logout
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /logout — 正常系: 200 を返却する")
    void logout_success_returns200() throws Exception {
        doNothing().when(authService).logout(any(), any(), any(Long.class));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token_hash", "hash-value"))
                        .param("jti", "jti-123")
                        .param("exp", "9999999999"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /logout — access_token と refresh_token の両方を maxAge=0 でクリアする")
    void logout_clearsBothCookies() throws Exception {
        doNothing().when(authService).logout(any(), any(), any(Long.class));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .param("jti", "jti-123")
                        .param("exp", "9999999999"))
                .andExpect(status().isOk())
                // access_token クリア（maxAge=0）
                .andExpect(cookie().maxAge("access_token", 0))
                // refresh_token クリア（maxAge=0）
                .andExpect(cookie().maxAge("refresh_token", 0))
                .andExpect(cookie().value("refresh_token", ""))
                .andExpect(header().stringValues(org.springframework.http.HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.allOf(
                                        containsString("refresh_token="),
                                        containsString("Max-Age=0")))));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/password-reset/request
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /password-reset/request — 正常系: 200 でメッセージを返却する")
    void passwordResetRequest_success_returns200() throws Exception {
        var msgResp = MessageResponse.of("パスワードリセットメールを送信しました");
        given(authService.requestPasswordReset(anyString(), anyString()))
                .willReturn(ApiResponse.of(msgResp));

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"test@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("パスワードリセットメールを送信しました"));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/auth/password-reset/confirm
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /password-reset/confirm — 正常系: 200 でメッセージを返却する")
    void passwordResetConfirm_success_returns200() throws Exception {
        var msgResp = MessageResponse.of("パスワードをリセットしました");
        given(authService.confirmPasswordReset(any(ConfirmPasswordResetRequest.class)))
                .willReturn(ApiResponse.of(msgResp));

        String body = """
                {
                  "token": "reset-token-abc",
                  "newPassword": "NewPassw0rd!"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("パスワードをリセットしました"));
    }

    // ──────────────────────────────────────────────
    // POST /register — JSON パース不能
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /register — 異常系: 不正 JSON で 400 を返却する")
    void register_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json }"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    // ──────────────────────────────────────────────
    // 認可根治戦役 Wave5 ロットB — 自己スコープ契約テスト
    // AuthLoginController#logoutAllDevices / AuthLoginController#getSessions
    //
    // SecurityContextHolder に userId=1 を設定し、Service へ渡る userId が
    // 常にその値と一致すること（=リクエストパラメータで他人の userId を
    // 指定する余地が無いこと）を厳密一致スタブで固定する。
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("自己スコープ契約（Wave5 ロットB）")
    class SelfScopeContract {

        private static final long SELF_USER_ID = 1L;

        @BeforeEach
        void setUpSecurityContext() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(String.valueOf(SELF_USER_ID), null, List.of()));
        }

        @AfterEach
        void clearSecurityContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("DELETE /sessions — 無効化対象は認証主体の userId のみ"
                + "（AuthLoginController#logoutAllDevices）")
        void logoutAllDevices_targetsOnlyAuthenticatedUser() throws Exception {
            doNothing().when(authService)
                    .logoutAllDevices(eq(SELF_USER_ID), any(), any(), any(Boolean.class));

            mockMvc.perform(delete("/api/v1/auth/sessions"))
                    .andExpect(status().isNoContent());
            // eq(SELF_USER_ID) スタブに一致した時点で、SecurityContext 以外から
            // 対象ユーザーが決まらないことが確認できる（不一致なら Mockito が未スタブ呼び出しとして検知する）。
        }

        @Test
        @DisplayName("GET /sessions — 返るのは認証主体自身のセッション一覧のみ"
                + "（AuthLoginController#getSessions）")
        void getSessions_returnsOnlyAuthenticatedUsersSessions() throws Exception {
            var session = new SessionResponse(
                    100L, "自分のPC", "DESKTOP", "127.0.0.1", "Mozilla/5.0",
                    false, LocalDateTime.of(2026, 7, 1, 10, 0),
                    LocalDateTime.of(2026, 8, 1, 10, 0),
                    LocalDateTime.of(2026, 8, 8, 10, 0), true);
            given(authService.getSessions(eq(SELF_USER_ID), any(), any()))
                    .willReturn(ApiResponse.of(List.of(session)));

            mockMvc.perform(get("/api/v1/auth/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(100))
                    .andExpect(jsonPath("$.data[0].deviceName").value("自分のPC"));
        }
    }
}
