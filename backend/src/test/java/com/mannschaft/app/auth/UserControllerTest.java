package com.mannschaft.app.auth;

import com.mannschaft.app.auth.controller.UserController;
import com.mannschaft.app.auth.service.AuthOAuthService;
import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.auth.dto.ChangePasswordRequest;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RequestEmailChangeRequest;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import com.mannschaft.app.auth.dto.UpdatePublicProfileRequest;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link UserController} の結合テスト。
 * {@code @WebMvcTest} でコントローラー層のみをロードし、Service は MockitoBean で差し替える。
 */
@WebMvcTest(UserController.class)
@org.springframework.context.annotation.Import(
        com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthOAuthService authOAuthService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

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

    @BeforeEach
    void setUpSecurityContext() {
        // SecurityUtils.getCurrentUserId() が userId=1 を返すよう認証情報をセット
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ──────────────────────────────────────────────
    // GET /api/v1/users/me
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /me — 正常系: 200 でプロフィールを返却する（UserController#getMyProfile）")
    void getMe_success_returns200() throws Exception {
        var profile = new UserProfileResponse(
                1L, "test@example.com", "田中", "太郎",
                "タナカ", "タロウ", "taro", null,
                true, null, "090-1234-5678", "150-0001",
                "ja", null, "Asia/Tokyo", "ACTIVE",
                true, false, 0, List.of("GOOGLE"),
                LocalDateTime.of(2026, 3, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0), null, false);
        given(userService.getUserProfile(anyLong()))
                .willReturn(ApiResponse.of(profile));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("taro"));
    }

    // ──────────────────────────────────────────────
    // PUT /api/v1/users/me
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PUT /me — 正常系: 200 で更新後プロフィールを返却する（UserController#updateMyProfile）")
    void updateMe_success_returns200() throws Exception {
        var updatedProfile = new UserProfileResponse(
                1L, "test@example.com", "佐藤", "花子",
                "サトウ", "ハナコ", "hanako", null,
                true, null, "090-9876-5432", "150-0001",
                "ja", null, "Asia/Tokyo", "ACTIVE",
                true, false, 0, List.of(),
                LocalDateTime.of(2026, 3, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0), null, false);
        given(userService.updateProfile(anyLong(), any(UpdateProfileRequest.class)))
                .willReturn(ApiResponse.of(updatedProfile));

        String body = """
                {
                  "lastName": "佐藤",
                  "firstName": "花子",
                  "nickname": "hanako"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.lastName").value("佐藤"))
                .andExpect(jsonPath("$.data.nickname").value("hanako"));
    }

    // ──────────────────────────────────────────────
    // PATCH /api/v1/users/me/password
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me/password — 正常系: 200 でメッセージを返却する")
    void changePassword_success_returns200() throws Exception {
        doNothing().when(userService).changePassword(anyLong(), any(ChangePasswordRequest.class), anyString());

        String body = """
                {
                  "currentPassword": "OldPassw0rd!",
                  "newPassword": "NewPassw0rd!"
                }
                """;

        mockMvc.perform(patch("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("パスワードを変更しました"));
    }

    // ──────────────────────────────────────────────
    // PATCH /api/v1/users/me/email
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /me/email — 正常系: 200 でメッセージを返却する")
    void requestEmailChange_success_returns200() throws Exception {
        var msgResp = MessageResponse.of("確認メールを送信しました");
        given(userService.requestEmailChange(anyLong(), any(RequestEmailChangeRequest.class)))
                .willReturn(ApiResponse.of(msgResp));

        String body = """
                {
                  "newEmail": "new@example.com",
                  "currentPassword": "Passw0rd!"
                }
                """;

        mockMvc.perform(patch("/api/v1/users/me/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("確認メールを送信しました"));
    }

    // ──────────────────────────────────────────────
    // DELETE /api/v1/users/me
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /me — 正常系: 200 で論理削除メッセージを返却する")
    void requestWithdrawal_success_returns200() throws Exception {
        doNothing().when(userService).requestWithdrawal(anyLong(), any(RequestWithdrawalRequest.class));

        String body = """
                {
                  "currentPassword": "Passw0rd!"
                }
                """;

        mockMvc.perform(delete("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("退会リクエストを受け付けました"));
    }

    // ──────────────────────────────────────────────
    // GET /api/v1/users/me/login-history
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /me/login-history — 正常系: 200 でログイン履歴を返却する")
    void getLoginHistory_success_returns200() throws Exception {
        var history = new LoginHistoryResponse(
                100L, "LOGIN_SUCCESS", "127.0.0.1",
                "Mozilla/5.0", "EMAIL_PASSWORD",
                LocalDateTime.of(2026, 3, 19, 12, 0));
        var meta = new CursorPagedResponse.CursorMeta(null, false, 20);
        CursorPagedResponse<LoginHistoryResponse> pagedResp =
                CursorPagedResponse.of(List.of(history), meta);
        given(authService.getLoginHistory(anyLong(), any(), anyInt(), any(), any()))
                .willReturn(pagedResp);

        mockMvc.perform(get("/api/v1/users/me/login-history")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].eventType").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    // ──────────────────────────────────────────────
    // PATCH /api/v1/users/me/public-profile (VIS-001〜003)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("VIS-001: PATCH /me/public-profile — enabled=true で 204 を返す"
            + "（UserController#updatePublicProfile）")
    void updatePublicProfile_enableTrue_returns204() throws Exception {
        doNothing().when(userService).updatePublicProfileEnabled(anyLong(), any(Boolean.class));

        String body = """
                {
                  "publicProfileEnabled": true
                }
                """;

        mockMvc.perform(patch("/api/v1/users/me/public-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("VIS-002: PATCH /me/public-profile — enabled=false で 204 を返す（トグル OFF）")
    void updatePublicProfile_enableFalse_returns204() throws Exception {
        doNothing().when(userService).updatePublicProfileEnabled(anyLong(), any(Boolean.class));

        String body = """
                {
                  "publicProfileEnabled": false
                }
                """;

        mockMvc.perform(patch("/api/v1/users/me/public-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("VIS-003: PATCH /me/public-profile — publicProfileEnabled 欠落で 400 を返す")
    void updatePublicProfile_missingField_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/public-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VIS-004: GET /me — publicProfileEnabled フィールドが返却される")
    void getMe_publicProfileEnabled_presentInResponse() throws Exception {
        var profile = new UserProfileResponse(
                1L, "test@example.com", "田中", "太郎",
                "タナカ", "タロウ", "taro", null,
                true, null, "090-1234-5678", "150-0001",
                "ja", null, "Asia/Tokyo", "ACTIVE",
                true, false, 0, List.of("GOOGLE"),
                LocalDateTime.of(2026, 3, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0), null, true);
        given(userService.getUserProfile(anyLong()))
                .willReturn(ApiResponse.of(profile));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicProfileEnabled").value(true));
    }

    // ──────────────────────────────────────────────
    // PUT /me — バリデーション確認（追加ケース）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PUT /me — 正常系: リクエストボディ空でも 200（任意項目のみ）")
    void updateMe_emptyBody_returns200() throws Exception {
        var profile = new UserProfileResponse(
                1L, "test@example.com", "田中", "太郎",
                null, null, "taro", null,
                null, null, null, null,
                "ja", null, "Asia/Tokyo", "ACTIVE",
                true, false, 0, List.of(),
                null, LocalDateTime.of(2026, 1, 1, 0, 0), null, false);
        given(userService.updateProfile(anyLong(), any(UpdateProfileRequest.class)))
                .willReturn(ApiResponse.of(profile));

        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    // ──────────────────────────────────────────────
    // F08.9 P3b: 後見切替セッション中（acting-as）の認証クリティカル操作ガード（03_security §3.2）
    //   ProxyInputContext.isProxy()==true で対象 EP は 403。
    // ──────────────────────────────────────────────

    @org.junit.jupiter.api.Nested
    @DisplayName("F08.9 P3b — 後見切替中の認証クリティカル操作ガード")
    class GuardianshipActingAsGuard {

        @org.junit.jupiter.api.BeforeEach
        void actingAs() {
            // 後見切替セッション中（X-Proxy-For-User-Id 検証済み）を模擬
            given(proxyInputContext.isProxy()).willReturn(true);
        }

        @Test
        @DisplayName("PATCH /me/password — 切替中は 403（パスワード変更を代理不可）")
        void changePassword_actingAs_returns403() throws Exception {
            String body = """
                    {
                      "currentPassword": "OldPassw0rd!",
                      "newPassword": "NewPassw0rd!"
                    }
                    """;

            mockMvc.perform(patch("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());

            org.mockito.Mockito.verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("PATCH /me/email — 切替中は 403（メール変更を代理不可）")
        void requestEmailChange_actingAs_returns403() throws Exception {
            String body = """
                    {
                      "newEmail": "new@example.com",
                      "currentPassword": "Passw0rd!"
                    }
                    """;

            mockMvc.perform(patch("/api/v1/users/me/email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());

            org.mockito.Mockito.verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("DELETE /me — 切替中は 403（退会を代理不可）")
        void requestWithdrawal_actingAs_returns403() throws Exception {
            String body = """
                    {
                      "currentPassword": "Passw0rd!"
                    }
                    """;

            mockMvc.perform(delete("/api/v1/users/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());

            org.mockito.Mockito.verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("POST /me/withdrawal/cancel — 切替中は 403（退会取消を代理不可）")
        void cancelWithdrawal_actingAs_returns403() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/users/me/withdrawal/cancel"))
                    .andExpect(status().isForbidden());

            org.mockito.Mockito.verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("POST /me/email/confirm — 切替中は 403（メール変更確認を代理不可・トークン迂回経路を塞ぐ）")
        void confirmEmailChange_actingAs_returns403() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/users/me/email/confirm")
                            .param("token", "test-token-value"))
                    .andExpect(status().isForbidden());

            org.mockito.Mockito.verifyNoInteractions(userService);
        }
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/users/me/withdrawal/cancel
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /me/withdrawal/cancel — 正常系: 200 で退会取消メッセージを返却する")
    void cancelWithdrawal_success_returns200() throws Exception {
        var msgResp = MessageResponse.of("退会リクエストを取り消しました");
        given(userService.cancelWithdrawal(anyLong()))
                .willReturn(ApiResponse.of(msgResp));
        // 通常入力（本人操作）: isProxy()==false
        given(proxyInputContext.isProxy()).willReturn(false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/users/me/withdrawal/cancel"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.message").value("退会リクエストを取り消しました"));
    }

    // ──────────────────────────────────────────────
    // 認可根治戦役 Wave5 ロットB — 自己スコープ契約テスト
    // UserController#setupPassword / UserController#getConnectedProviders /
    // UserController#disconnectProvider
    //
    // SecurityContextHolder に userId=1 を設定済み（クラス @BeforeEach）。
    // Service への引数を厳密一致（eq）でスタブし、他ユーザーの識別子が紛れ込む余地が
    // 無いこと（＝リクエストからではなく認証主体からのみ userId が決まること）を固定する。
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /me/password/setup — 対象は認証主体の userId のみ（UserController#setupPassword）")
    void setupPassword_targetsOnlyAuthenticatedUser() throws Exception {
        var msgResp = MessageResponse.of("パスワードを設定しました");
        given(userService.setupPassword(eq(1L), anyString()))
                .willReturn(ApiResponse.of(msgResp));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/users/me/password/setup")
                        .param("password", "NewPassw0rd!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("パスワードを設定しました"));
    }

    @Test
    @DisplayName("GET /me/oauth — 返るのは認証主体自身の連携一覧のみ（UserController#getConnectedProviders）")
    void getConnectedProviders_returnsOnlyAuthenticatedUsersProviders() throws Exception {
        var provider = new OAuthProviderResponse("GOOGLE", "self@example.com",
                LocalDateTime.of(2026, 1, 1, 0, 0));
        given(authOAuthService.getConnectedProviders(eq(1L)))
                .willReturn(ApiResponse.of(List.of(provider)));

        mockMvc.perform(get("/api/v1/users/me/oauth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data[0].providerEmail").value("self@example.com"));
    }

    @Test
    @DisplayName("DELETE /me/oauth/{provider} — 解除対象は認証主体の userId のみ"
            + "（UserController#disconnectProvider — provider はプロバイダ種別に過ぎない）")
    void disconnectProvider_targetsOnlyAuthenticatedUser() throws Exception {
        doNothing().when(authOAuthService).disconnectProvider(eq(1L), eq("GOOGLE"));

        mockMvc.perform(delete("/api/v1/users/me/oauth/GOOGLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("OAuth連携を解除しました"));
    }
}
