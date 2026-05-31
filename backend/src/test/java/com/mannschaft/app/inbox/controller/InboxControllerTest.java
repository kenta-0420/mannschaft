package com.mannschaft.app.inbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.InboxSummaryResponse;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.service.InboxAggregationService;
import com.mannschaft.app.inbox.service.InboxTriageService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F04.11 {@link InboxController} MockMvc 契約テスト（@WebMvcTest）。
 *
 * <p>設計書 02_api_design.md §2・§3 のレスポンス形・クエリ伝播・バリデーション・エラーコード→HTTP を検証する。
 * フィルタは {@code addFilters=false} で無効化し、認証は SecurityContext に直接セットする
 * （手本: {@code FavoriteControllerTest}）。サービスは {@link MockitoBean} でモックする。</p>
 *
 * <p><b>green 想定</b>: 骨格コントローラーの委譲が正しければ本テストは通る
 * （サービス本体の未実装は MockitoBean で吸収されるため契約は検証可能）。</p>
 */
@WebMvcTest(InboxController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("InboxController 契約テスト")
class InboxControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InboxAggregationService aggregationService;

    @MockitoBean
    private InboxTriageService triageService;

    // JwtAuthenticationFilter 依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // UserLocaleFilter 依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // ProxyInputContextFilter の依存解決用
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────────────

    private InboxItemDto sampleItem() {
        return new InboxItemDto(
                "NOTIFICATION:123", InboxSourceType.NOTIFICATION, 123L, "タイトル", "抜粋",
                InboxPriority.HIGH, null, "/x/123",
                LocalDateTime.of(2026, 5, 31, 9, 0), InboxState.UNREAD, null, List.of());
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/inbox
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/inbox")
    class GetInbox {

        @Test
        @DisplayName("正常系: 200・data.{items,page,size,totalEstimated,hasMore} 形で返る")
        void getInbox_200_responseShape() throws Exception {
            InboxPageResponse page = new InboxPageResponse(List.of(sampleItem()), 0, 20, 1L, false);
            given(aggregationService.getInbox(eq(USER_ID), any(), any(), any(), any(), eq(0), eq(20)))
                    .willReturn(page);

            mockMvc.perform(get("/api/v1/inbox"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].id").value("NOTIFICATION:123"))
                    .andExpect(jsonPath("$.data.items[0].sourceType").value("NOTIFICATION"))
                    .andExpect(jsonPath("$.data.items[0].priority").value("HIGH"))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.totalEstimated").value(1))
                    .andExpect(jsonPath("$.data.hasMore").value(false));
        }

        @Test
        @DisplayName("正常系: クエリ state/priority/sourceType/page/size が Service へ正しく渡る")
        void getInbox_passesQueryParams() throws Exception {
            given(aggregationService.getInbox(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt()))
                    .willReturn(new InboxPageResponse(List.of(), 2, 10, 0L, false));

            mockMvc.perform(get("/api/v1/inbox")
                            .param("state", "ARCHIVED")
                            .param("priority", "URGENT", "HIGH")
                            .param("sourceType", "NOTIFICATION")
                            .param("page", "2")
                            .param("size", "10"))
                    .andExpect(status().isOk());

            verify(aggregationService).getInbox(
                    eq(USER_ID),
                    eq("ARCHIVED"),
                    eq(List.of(InboxPriority.URGENT, InboxPriority.HIGH)),
                    eq(List.of(InboxSourceType.NOTIFICATION)),
                    eq(null),
                    eq(2),
                    eq(10));
        }

        @Test
        @DisplayName("認可: 未認証（SecurityContext なし）→ 401 COMMON_000")
        void getInbox_unauthenticated_401() throws Exception {
            SecurityContextHolder.clearContext();

            mockMvc.perform(get("/api/v1/inbox"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("COMMON_000"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/inbox/summary
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/inbox/summary")
    class GetSummary {

        @Test
        @DisplayName("正常系: 200・byState/byPriority/bySourceType が返る")
        void getSummary_200() throws Exception {
            InboxSummaryResponse summary = new InboxSummaryResponse(
                    Map.of("INBOX", 12L, "SNOOZED", 3L),
                    Map.of("URGENT", 2L, "HIGH", 5L),
                    Map.of("NOTIFICATION", 4L));
            given(aggregationService.getSummary(USER_ID)).willReturn(summary);

            mockMvc.perform(get("/api/v1/inbox/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.byState.INBOX").value(12))
                    .andExpect(jsonPath("$.data.byPriority.URGENT").value(2))
                    .andExpect(jsonPath("$.data.bySourceType.NOTIFICATION").value(4));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // POST /api/v1/inbox/snooze
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/inbox/snooze")
    class Snooze {

        @Test
        @DisplayName("正常系: 未来時刻 → 200・更新後 InboxItem が返る")
        void snooze_200() throws Exception {
            given(triageService.snooze(eq(USER_ID), eq(InboxSourceType.NOTIFICATION), eq(123L), any()))
                    .willReturn(sampleItem());

            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "NOTIFICATION",
                    "sourceId", 123,
                    "snoozedUntil", LocalDateTime.now().plusHours(3).toString()));

            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("NOTIFICATION:123"));
        }

        @Test
        @DisplayName("異常系: snoozedUntil 欠落 → 400 COMMON_001（@NotNull）")
        void snooze_missingSnoozedUntil_400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "NOTIFICATION",
                    "sourceId", 123));

            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("異常系: snoozedUntil が過去 → 400 COMMON_001（@Future）")
        void snooze_pastSnoozedUntil_400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "NOTIFICATION",
                    "sourceId", 123,
                    "snoozedUntil", LocalDateTime.now().minusHours(1).toString()));

            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("異常系: サービスが INBOX_SOURCE_NOT_FOUND → 404")
        void snooze_sourceNotFound_404() throws Exception {
            given(triageService.snooze(eq(USER_ID), any(), any(), any()))
                    .willThrow(new BusinessException(InboxErrorCode.INBOX_SOURCE_NOT_FOUND));

            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "NOTIFICATION",
                    "sourceId", 999,
                    "snoozedUntil", LocalDateTime.now().plusHours(3).toString()));

            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_SOURCE_NOT_FOUND"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // POST /api/v1/inbox/{unsnooze,archive,unarchive}
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST triage（unsnooze / archive / unarchive）")
    class Triage {

        @Test
        @DisplayName("archive 正常系: {sourceType,sourceId} → 200")
        void archive_200() throws Exception {
            given(triageService.archive(eq(USER_ID), eq(InboxSourceType.ANNOUNCEMENT), eq(45L)))
                    .willReturn(sampleItem());

            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "ANNOUNCEMENT", "sourceId", 45));

            mockMvc.perform(post("/api/v1/inbox/archive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("NOTIFICATION:123"));
        }

        @Test
        @DisplayName("unsnooze 正常系: {sourceType,sourceId} → 200")
        void unsnooze_200() throws Exception {
            given(triageService.unsnooze(eq(USER_ID), eq(InboxSourceType.NOTIFICATION), eq(123L)))
                    .willReturn(sampleItem());

            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "NOTIFICATION", "sourceId", 123));

            mockMvc.perform(post("/api/v1/inbox/unsnooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("unarchive 正常系: {sourceType,sourceId} → 200")
        void unarchive_200() throws Exception {
            given(triageService.unarchive(eq(USER_ID), eq(InboxSourceType.NOTIFICATION), eq(123L)))
                    .willReturn(sampleItem());

            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "NOTIFICATION", "sourceId", 123));

            mockMvc.perform(post("/api/v1/inbox/unarchive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("archive 異常系: sourceType 欠落 → 400 COMMON_001")
        void archive_missingSourceType_400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("sourceId", 45));

            mockMvc.perform(post("/api/v1/inbox/archive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }
}
