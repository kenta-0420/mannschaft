package com.mannschaft.app.sync.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.sync.SyncErrorCode;
import com.mannschaft.app.sync.dto.ConflictDetailResponse;
import com.mannschaft.app.sync.dto.ConflictResponse;
import com.mannschaft.app.sync.dto.SyncResponse;
import com.mannschaft.app.sync.dto.SyncResultItem;
import com.mannschaft.app.sync.dto.SyncSummary;
import com.mannschaft.app.sync.service.ConflictResolverService;
import com.mannschaft.app.sync.service.OfflineSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link OfflineSyncController} のコントローラーテスト。
 */
@WebMvcTest(OfflineSyncController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OfflineSyncController テスト")
class OfflineSyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OfflineSyncService syncService;

    @MockitoBean
    private ConflictResolverService conflictResolverService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ========================================
    // POST /api/v1/sync 正常系
    // ========================================

    @Nested
    @DisplayName("POST /api/v1/sync")
    class PostSync {

        @Test
        @DisplayName("正常系: 同期リクエストが処理され 200 が返される")
        void sync_正常系_200() throws Exception {
            // given
            SyncResponse response = new SyncResponse(
                    List.of(SyncResultItem.success("c1", 1L)),
                    new SyncSummary(1, 1, 0, 0));
            given(syncService.sync(eq(USER_ID), any())).willReturn(response);

            String body = """
                    {
                      "items": [
                        {
                          "clientId": "c1",
                          "method": "POST",
                          "path": "/api/v1/activities",
                          "body": {"title": "test"},
                          "createdAt": "2026-04-10T10:00:00"
                        }
                      ]
                    }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/sync")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results[0].clientId").value("c1"))
                    .andExpect(jsonPath("$.data.results[0].status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.summary.total").value(1))
                    .andExpect(jsonPath("$.data.summary.success").value(1));
        }
    }

    // ========================================
    // GET /api/v1/sync/conflicts/me 正常系
    // ========================================

    @Nested
    @DisplayName("GET /api/v1/sync/conflicts/me")
    class GetConflictsMe {

        @Test
        @DisplayName("正常系: 未解決コンフリクト一覧が取得できる")
        void getMyConflicts_正常系() throws Exception {
            // given
            ConflictResponse conflict = new ConflictResponse(
                    100L, "activities", 50L, 5L, 6L, null, null,
                    LocalDateTime.of(2026, 4, 10, 10, 0));
            given(conflictResolverService.getMyConflicts(eq(USER_ID), any()))
                    .willReturn(new PageImpl<>(List.of(conflict), PageRequest.of(0, 20), 1));

            // when & then
            mockMvc.perform(get("/api/v1/sync/conflicts/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(100))
                    .andExpect(jsonPath("$.data[0].resourceType").value("activities"))
                    .andExpect(jsonPath("$.meta.total").value(1));
        }
    }

    // ========================================
    // PATCH /api/v1/sync/conflicts/{id}/resolve 正常系
    // ========================================

    @Nested
    @DisplayName("PATCH /api/v1/sync/conflicts/{id}/resolve")
    class ResolveConflict {

        @Test
        @DisplayName("正常系: CLIENT_WIN で解決される")
        void resolveConflict_正常系() throws Exception {
            // given
            ConflictDetailResponse detail = new ConflictDetailResponse(
                    100L, USER_ID, "activities", 50L,
                    "{\"title\":\"client\"}", "{\"title\":\"server\"}",
                    5L, 6L, "CLIENT_WIN", LocalDateTime.now(),
                    LocalDateTime.of(2026, 4, 10, 10, 0),
                    LocalDateTime.of(2026, 4, 10, 10, 5));
            given(conflictResolverService.resolveConflict(eq(100L), eq(USER_ID), any()))
                    .willReturn(detail);

            String body = """
                    {
                      "resolution": "CLIENT_WIN"
                    }
                    """;

            // when & then
            mockMvc.perform(patch("/api/v1/sync/conflicts/100/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolution").value("CLIENT_WIN"))
                    .andExpect(jsonPath("$.data.resolvedAt").exists());
        }
    }

    // ========================================
    // DELETE /api/v1/sync/conflicts/{id} 正常系
    // ========================================

    @Nested
    @DisplayName("DELETE /api/v1/sync/conflicts/{id}")
    class DiscardConflict {

        @Test
        @DisplayName("正常系: コンフリクト破棄で 204 が返される")
        void discardConflict_正常系() throws Exception {
            // given
            willDoNothing().given(conflictResolverService)
                    .discardConflict(100L, USER_ID);

            // when & then
            mockMvc.perform(delete("/api/v1/sync/conflicts/100"))
                    .andExpect(status().isNoContent());
        }
    }

    // ========================================
    // 権限チェック（他人のコンフリクト操作 → 404）
    // ========================================

    @Nested
    @DisplayName("権限チェック")
    class AuthorizationCheck {

        @Test
        @DisplayName("他人のコンフリクト詳細取得 → 404")
        void getConflictDetail_他人_404() throws Exception {
            // given
            given(conflictResolverService.getConflictDetail(eq(999L), eq(USER_ID)))
                    .willThrow(new BusinessException(SyncErrorCode.CONFLICT_NOT_FOUND));

            // when & then
            mockMvc.perform(get("/api/v1/sync/conflicts/999"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("他人のコンフリクト解決 → 404")
        void resolveConflict_他人_404() throws Exception {
            // given
            given(conflictResolverService.resolveConflict(eq(999L), eq(USER_ID), any()))
                    .willThrow(new BusinessException(SyncErrorCode.CONFLICT_NOT_FOUND));

            String body = """
                    {
                      "resolution": "CLIENT_WIN"
                    }
                    """;

            // when & then
            mockMvc.perform(patch("/api/v1/sync/conflicts/999/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("他人のコンフリクト破棄 → 404")
        void discardConflict_他人_404() throws Exception {
            // given
            willThrow(new BusinessException(SyncErrorCode.CONFLICT_NOT_FOUND))
                    .given(conflictResolverService).discardConflict(999L, USER_ID);

            // when & then
            mockMvc.perform(delete("/api/v1/sync/conflicts/999"))
                    .andExpect(status().isNotFound());
        }
    }
}
