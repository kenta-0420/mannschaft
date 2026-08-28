package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.AuditEventCategory;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.CursorPagedResponse;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuditLogAdminController} の結合テスト。
 * {@code @WebMvcTest} でコントローラー層のみをロードし、Service は MockitoBean で差し替える。
 *
 * <p>認可根治戦役 Wave5 ロットB — 自己スコープ契約テストを兼ねる。
 * {@code getMyLogs} はクラス名に {@code Admin} を含むが、対象は
 * {@code SecurityUtils.getCurrentUserId()} のみで解決される自分専用エンドポイントであり、
 * リクエストに他人の {@code userId} を指定する項目は存在しない
 * （{@code AuditLogAdminController#getMyLogs}）。</p>
 */
@WebMvcTest(AuditLogAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuditLogAdminControllerTest {

    private static final Long SELF_USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    // ── @WebMvcTest コンテキストの依存解決用 ──
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SELF_USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /me/audit-logs — 返るのは認証主体自身のログのみ"
            + "（AuditLogAdminController#getMyLogs — userId は SecurityContext からのみ決まる）")
    void getMyLogs_returnsOnlyAuthenticatedUsersLogs() throws Exception {
        var log = AuditLogResponse.builder()
                .id(500L)
                .userId(SELF_USER_ID)
                .eventType("LOGIN_SUCCESS")
                .createdAt(LocalDateTime.of(2026, 8, 1, 9, 0))
                .build();
        var meta = new CursorPagedResponse.CursorMeta(null, false, 20);
        given(auditLogService.getMyLogs(eq(SELF_USER_ID), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(CursorPagedResponse.of(List.of(log), meta));

        mockMvc.perform(get("/api/v1/users/me/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(500))
                .andExpect(jsonPath("$.data[0].userId").value(SELF_USER_ID))
                .andExpect(jsonPath("$.data[0].eventType").value("LOGIN_SUCCESS"));
    }

    @Test
    @DisplayName("GET /me/audit-logs — eventCategory を指定しても対象ユーザーは変わらない"
            + "（AuditLogAdminController#getMyLogs）")
    void getMyLogs_withEventCategory_stillScopedToSelf() throws Exception {
        given(auditLogService.getMyLogs(eq(SELF_USER_ID), any(), eq(List.of(AuditEventCategory.AUTH)),
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .willReturn(CursorPagedResponse.of(List.of(), new CursorPagedResponse.CursorMeta(null, false, 20)));

        mockMvc.perform(get("/api/v1/users/me/audit-logs")
                        .param("eventCategory", "AUTH"))
                .andExpect(status().isOk());
    }
}
