package com.mannschaft.app.role.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F15.3 §5.1.1 {@link InviteController#joinByInvite} のフォルダ受領機能を検証する MockMvc テスト。
 *
 * <ul>
 *   <li>{@code folderId} 指定時 → 指定フォルダへ配置（assigned_via=INVITE）</li>
 *   <li>{@code folderId} 未指定時 → 未分類フォルダへ配置（assigned_via=DEFAULT）</li>
 *   <li>ボディ全体無しでも動作（後方互換）</li>
 *   <li>他人のフォルダ ID → SCOPE_FOLDER_NOT_FOUND</li>
 *   <li>scope_type 不一致（TEAM 招待 × ORGANIZATION フォルダ）→ SCOPE_FOLDER_TYPE_MISMATCH</li>
 * </ul>
 *
 * <p>サービスは {@link MockitoBean} で差し替え、Controller の DTO 受領 + 引数渡しのみを検証する。
 * 実際の参加処理・フォルダ配置ロジックは {@code InviteServiceTest} / {@code MyScopeFolderServiceTest} の責務。</p>
 */
@WebMvcTest(InviteController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("InviteController フォルダ受領機能 結合テスト (F15.3)")
class InviteControllerFolderTest {

    private static final Long USER_ID = 1L;
    private static final String TOKEN = "abc-token-uuid";
    private static final Long FOLDER_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InviteService inviteService;

    // JwtAuthenticationFilter / UserLocaleFilter / ProxyInputContextFilter の依存解決用
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
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ════════════════════════════════════════════════
    // folderId 指定時
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/invite/{token}/join with folderId")
    class WithFolderId {

        @Test
        @DisplayName("正常系: folderId 指定 → InviteService.joinByInvite(token, userId, folderId) が呼ばれる")
        void joinByInvite_folderId指定_200() throws Exception {
            willDoNothing().given(inviteService).joinByInvite(eq(TOKEN), eq(USER_ID), eq(FOLDER_ID));

            String body = """
                    {
                      "folderId": %d
                    }
                    """.formatted(FOLDER_ID);

            mockMvc.perform(post("/api/v1/invite/{token}/join", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            verify(inviteService).joinByInvite(TOKEN, USER_ID, FOLDER_ID);
        }

        @Test
        @DisplayName("異常系: 他人所有のフォルダID → SCOPE_FOLDER_NOT_FOUND がレスポンスに反映")
        void joinByInvite_他人のフォルダ_SCOPE_FOLDER_NOT_FOUND() throws Exception {
            willThrow(new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND))
                    .given(inviteService).joinByInvite(eq(TOKEN), eq(USER_ID), eq(FOLDER_ID));

            String body = """
                    {
                      "folderId": %d
                    }
                    """.formatted(FOLDER_ID);

            mockMvc.perform(post("/api/v1/invite/{token}/join", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.error.code")
                            .value(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("異常系: scope_type 不一致（TEAM 招待で ORGANIZATION フォルダ指定）→ SCOPE_FOLDER_TYPE_MISMATCH")
        void joinByInvite_scope_type不一致_TYPE_MISMATCH() throws Exception {
            willThrow(new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH))
                    .given(inviteService).joinByInvite(eq(TOKEN), eq(USER_ID), eq(FOLDER_ID));

            String body = """
                    {
                      "folderId": %d
                    }
                    """.formatted(FOLDER_ID);

            mockMvc.perform(post("/api/v1/invite/{token}/join", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.error.code").value(
                            ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH.getCode()));
        }
    }

    // ════════════════════════════════════════════════
    // folderId 未指定時
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/invite/{token}/join without folderId")
    class WithoutFolderId {

        @Test
        @DisplayName("正常系: folderId=null → InviteService.joinByInvite(token, userId, null) が呼ばれる")
        void joinByInvite_folderId未指定_null渡し() throws Exception {
            willDoNothing().given(inviteService).joinByInvite(anyString(), eq(USER_ID), eq(null));

            String body = """
                    {
                    }
                    """;

            mockMvc.perform(post("/api/v1/invite/{token}/join", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            verify(inviteService).joinByInvite(TOKEN, USER_ID, null);
        }

        @Test
        @DisplayName("後方互換: ボディ自体が無くても 200 OK で参加できる")
        void joinByInvite_ボディ無し_後方互換() throws Exception {
            willDoNothing().given(inviteService).joinByInvite(anyString(), eq(USER_ID), eq(null));

            mockMvc.perform(post("/api/v1/invite/{token}/join", TOKEN))
                    .andExpect(status().isOk());

            verify(inviteService).joinByInvite(TOKEN, USER_ID, null);
        }
    }
}
