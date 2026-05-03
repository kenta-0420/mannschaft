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
import com.mannschaft.app.auth.dto.UpdateProfileRequest;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UserController} の結合テスト。
 * {@code @WebMvcTest} でコントローラー層のみをロードし、Service は MockitoBean で差し替える。
 */
@WebMvcTest(UserController.class)
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
    @DisplayName("GET /me — 正常系: 200 でプロフィールを返却する")
    void getMe_success_returns200() throws Exception {
        var profile = new UserProfileResponse(
                1L, "test@example.com", "田中", "太郎",
                "タナカ", "タロウ", "taro", null,
                true, null, "090-1234-5678",
                "ja", null, "Asia/Tokyo", "ACTIVE",
                true, false, 0, List.of("GOOGLE"),
                LocalDateTime.of(2026, 3, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0), null);
        given(userService.getUserProfile(anyLong()))
                .willReturn(ApiResponse.of(profile));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("taro"));
    }

    // ──────────────────────────────────────────────
    // PUT /api/v1/users/me
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PUT /me — 正常系: 200 で更新後プロフィールを返却する")
    void updateMe_success_returns200() throws Exception {
        var updatedProfile = new UserProfileResponse(
                1L, "test@example.com", "佐藤", "花子",
                "サトウ", "ハナコ", "hanako", null,
                true, null, "090-9876-5432",
                "ja", null, "Asia/Tokyo", "ACTIVE",
                true, false, 0, List.of(),
                LocalDateTime.of(2026, 3, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0), null);
        given(userService.updateProfile(anyLong(), any(UpdateProfileRequest.class)))
                .willReturn(ApiResponse.of(updatedProfile));

        String body = """
                {
                  "lastName": "佐藤",
                  "firstName": "花子",
                  "displayName": "hanako"
                }
                """;

        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.lastName").value("佐藤"))
                .andExpect(jsonPath("$.data.displayName").value("hanako"));
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
        given(authService.getLoginHistory(anyLong(), any(), anyInt()))
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
    // PUT /me — バリデーション確認（追加ケース）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PUT /me — 正常系: リクエストボディ空でも 200（任意項目のみ）")
    void updateMe_emptyBody_returns200() throws Exception {
        var profile = new UserProfileResponse(
                1L, "test@example.com", "田中", "太郎",
                null, null, "taro", null,
                null, null, null,
                "ja", null, "Asia/Tokyo", "ACTIVE",
                true, false, 0, List.of(),
                null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
        given(userService.updateProfile(anyLong(), any(UpdateProfileRequest.class)))
                .willReturn(ApiResponse.of(profile));

        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1));
    }
}
