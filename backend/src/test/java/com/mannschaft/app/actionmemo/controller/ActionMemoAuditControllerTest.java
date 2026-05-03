package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.service.ActionMemoService;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ActionMemoController} の監査ログエンドポイント（Phase 5-1）テスト。
 *
 * <ul>
 *   <li>所有者は 200 で履歴を取得できる</li>
 *   <li>非所有者（別ユーザーのメモ）は 404 が返る（IDOR 対策）</li>
 * </ul>
 */
@WebMvcTest(ActionMemoController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ActionMemoController 監査ログ取得テスト（Phase 5-1）")
class ActionMemoAuditControllerTest {

    private static final Long OWNER_USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long MEMO_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActionMemoService actionMemoService;

    @MockitoBean
    private com.mannschaft.app.actionmemo.service.ActionMemoTagService actionMemoTagService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(OWNER_USER_ID, null, List.of()));
    }

    @Test
    @DisplayName("所有者: 200 で監査ログを取得できる")
    void getMemoAuditLogs_asOwner_returnsLogs() throws Exception {
        AuditLogResponse log1 = AuditLogResponse.builder()
                .id(1L)
                .userId(OWNER_USER_ID)
                .eventType("ACTION_MEMO_CREATED")
                .metadata("{\"source\":\"ACTION_MEMO\",\"source_id\":100,\"event\":\"CREATED\",\"category\":\"PRIVATE\"}")
                .createdAt(LocalDateTime.of(2026, 5, 3, 10, 0))
                .build();
        AuditLogResponse log2 = AuditLogResponse.builder()
                .id(2L)
                .userId(OWNER_USER_ID)
                .eventType("ACTION_MEMO_UPDATED")
                .metadata("{\"source\":\"ACTION_MEMO\",\"source_id\":100,\"event\":\"UPDATED\",\"fields_changed\":\"content\"}")
                .createdAt(LocalDateTime.of(2026, 5, 3, 10, 5))
                .build();

        given(actionMemoService.getMemoAuditLogs(eq(MEMO_ID), eq(OWNER_USER_ID)))
                .willReturn(List.of(log1, log2));

        mockMvc.perform(get("/api/v1/action-memos/{id}/audit-logs", MEMO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].event_type").value("ACTION_MEMO_CREATED"))
                .andExpect(jsonPath("$.data[0].actor_id").value(OWNER_USER_ID))
                .andExpect(jsonPath("$.data[0].created_at").value("2026-05-03T10:00:00"))
                .andExpect(jsonPath("$.data[1].event_type").value("ACTION_MEMO_UPDATED"));
    }

    @Test
    @DisplayName("非所有者: 404 が返る（IDOR 対策）")
    void getMemoAuditLogs_asNonOwner_returns404() throws Exception {
        // 別ユーザーのメモにアクセスしようとした場合
        given(actionMemoService.getMemoAuditLogs(eq(MEMO_ID), eq(OWNER_USER_ID)))
                .willThrow(new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));

        mockMvc.perform(get("/api/v1/action-memos/{id}/audit-logs", MEMO_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
    }

    @Test
    @DisplayName("監査ログが0件の場合: 200 で空配列を返す")
    void getMemoAuditLogs_noLogs_returnsEmptyList() throws Exception {
        given(actionMemoService.getMemoAuditLogs(eq(MEMO_ID), eq(OWNER_USER_ID)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/action-memos/{id}/audit-logs", MEMO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
