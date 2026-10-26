package com.mannschaft.app.inbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.BulkResultResponse;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.InboxSummaryResponse;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.service.InboxAggregationService;
import com.mannschaft.app.inbox.service.InboxBulkService;
import com.mannschaft.app.inbox.service.InboxLabelService;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

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

    @MockitoBean
    private InboxLabelService labelService;

    @MockitoBean
    private InboxBulkService bulkService;

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

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

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
                LocalDateTime.of(2026, 5, 31, 9, 0), InboxState.UNREAD, null, List.of(),
                "NOTIFICATION:123", 1,
                List.of(new com.mannschaft.app.inbox.dto.InboxItemRef(
                        InboxSourceType.NOTIFICATION, 123L)));
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
                    "snoozedUntil", OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(3).toString()));

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
                    "snoozedUntil", OffsetDateTime.now(ZoneOffset.ofHours(9)).minusHours(1).toString()));

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
                    "snoozedUntil", OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(3).toString()));

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

    // ─────────────────────────────────────────────────────────────────
    // ラベル CRUD（Phase 2）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ラベル CRUD")
    class Labels {

        @Test
        @DisplayName("GET /labels: 200・data[] に LabelDto が返る")
        void getLabels_200() throws Exception {
            given(labelService.getLabels(USER_ID)).willReturn(List.of(
                    new LabelDto(UUID.randomUUID(), "経理", "#f59e0b", "pi-wallet", 0)));

            mockMvc.perform(get("/api/v1/inbox/labels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].name").value("経理"))
                    .andExpect(jsonPath("$.data[0].color").value("#f59e0b"))
                    .andExpect(jsonPath("$.data[0].sortOrder").value(0));
        }

        @Test
        @DisplayName("POST /labels: 201・作成 LabelDto を返す")
        void createLabel_201() throws Exception {
            UUID id = UUID.randomUUID();
            given(labelService.createLabel(eq(USER_ID), eq("要返信"), eq("#3b82f6"), eq("pi-reply")))
                    .willReturn(new LabelDto(id, "要返信", "#3b82f6", "pi-reply", 0));

            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "要返信", "color", "#3b82f6", "icon", "pi-reply"));

            mockMvc.perform(post("/api/v1/inbox/labels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.name").value("要返信"));
        }

        @Test
        @DisplayName("POST /labels: name 空 → 400 COMMON_001（@NotBlank）")
        void createLabel_blankName_400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("name", ""));

            mockMvc.perform(post("/api/v1/inbox/labels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("POST /labels: 上限超過 → 422 INBOX_LABEL_LIMIT_EXCEEDED")
        void createLabel_limit_422() throws Exception {
            given(labelService.createLabel(any(), any(), any(), any()))
                    .willThrow(new BusinessException(InboxErrorCode.INBOX_LABEL_LIMIT_EXCEEDED));

            String body = objectMapper.writeValueAsString(Map.of("name", "x"));

            mockMvc.perform(post("/api/v1/inbox/labels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_LIMIT_EXCEEDED"));
        }

        @Test
        @DisplayName("POST /labels: 同名重複 → 409 INBOX_LABEL_NAME_DUPLICATE")
        void createLabel_duplicate_409() throws Exception {
            given(labelService.createLabel(any(), any(), any(), any()))
                    .willThrow(new BusinessException(InboxErrorCode.INBOX_LABEL_NAME_DUPLICATE));

            String body = objectMapper.writeValueAsString(Map.of("name", "要返信"));

            mockMvc.perform(post("/api/v1/inbox/labels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_NAME_DUPLICATE"));
        }

        @Test
        @DisplayName("PUT /labels/{id}: 200・更新 LabelDto を返す")
        void updateLabel_200() throws Exception {
            UUID id = UUID.randomUUID();
            given(labelService.updateLabel(eq(USER_ID), eq(id), any(), any(), any(), any()))
                    .willReturn(new LabelDto(id, "新", "#123456", "pi-tag", 5));

            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "新", "color", "#123456", "icon", "pi-tag", "sortOrder", 5));

            mockMvc.perform(put("/api/v1/inbox/labels/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("新"));
        }

        @Test
        @DisplayName("PUT /labels/{id}: 他人ラベル → 404 INBOX_LABEL_NOT_FOUND")
        void updateLabel_notFound_404() throws Exception {
            UUID id = UUID.randomUUID();
            given(labelService.updateLabel(any(), any(), any(), any(), any(), any()))
                    .willThrow(new BusinessException(InboxErrorCode.INBOX_LABEL_NOT_FOUND));

            String body = objectMapper.writeValueAsString(Map.of("name", "x"));

            mockMvc.perform(put("/api/v1/inbox/labels/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_NOT_FOUND"));
        }

        @Test
        @DisplayName("DELETE /labels/{id}: 204")
        void deleteLabel_204() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete("/api/v1/inbox/labels/" + id))
                    .andExpect(status().isNoContent());

            verify(labelService).deleteLabel(USER_ID, id);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ラベル付与 / 解除（Phase 2）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ラベル付与 / 解除")
    class LabelAssign {

        @Test
        @DisplayName("POST /labels/{id}/assign: 204・サービスへ委譲")
        void assign_204() throws Exception {
            UUID id = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "MENTION", "sourceId", 9));

            mockMvc.perform(post("/api/v1/inbox/labels/" + id + "/assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());

            verify(labelService).assignLabel(USER_ID, id, InboxSourceType.MENTION, 9L);
        }

        @Test
        @DisplayName("POST /labels/{id}/assign: 1 通知上限超過 → 422")
        void assign_perItemLimit_422() throws Exception {
            UUID id = UUID.randomUUID();
            doThrow(new BusinessException(InboxErrorCode.INBOX_LABEL_PER_ITEM_EXCEEDED))
                    .when(labelService).assignLabel(any(), any(), any(), any());

            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "MENTION", "sourceId", 9));

            mockMvc.perform(post("/api/v1/inbox/labels/" + id + "/assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_PER_ITEM_EXCEEDED"));
        }

        @Test
        @DisplayName("DELETE /labels/{id}/assign: 204・サービスへ委譲")
        void unassign_204() throws Exception {
            UUID id = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of(
                    "sourceType", "MENTION", "sourceId", 9));

            mockMvc.perform(delete("/api/v1/inbox/labels/" + id + "/assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());

            verify(labelService).unassignLabel(USER_ID, id, InboxSourceType.MENTION, 9L);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // suggest-apply（自動ラベリング・案C・Phase 4）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/inbox/labels/suggest-apply")
    class SuggestApply {

        @Test
        @DisplayName("正常系: 200・付与済み LabelDto を返す・サービスへ委譲")
        void suggestApply_200() throws Exception {
            UUID labelId = UUID.randomUUID();
            given(labelService.suggestApply(
                    eq(USER_ID), eq("要返信"), eq("#2563EB"), eq(InboxSourceType.MENTION), eq(9L)))
                    .willReturn(new LabelDto(labelId, "要返信", "#2563EB", null, 0));

            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "要返信", "color", "#2563EB",
                    "sourceType", "MENTION", "sourceId", 9));

            mockMvc.perform(post("/api/v1/inbox/labels/suggest-apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("要返信"));

            verify(labelService).suggestApply(
                    USER_ID, "要返信", "#2563EB", InboxSourceType.MENTION, 9L);
        }

        @Test
        @DisplayName("異常系: name 空 → 400（@NotBlank）")
        void suggestApply_blankName_400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "", "color", "#2563EB",
                    "sourceType", "MENTION", "sourceId", 9));

            mockMvc.perform(post("/api/v1/inbox/labels/suggest-apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("異常系: 上限超過 → 422 INBOX_LABEL_LIMIT_EXCEEDED")
        void suggestApply_limit_422() throws Exception {
            given(labelService.suggestApply(any(), any(), any(), any(), any()))
                    .willThrow(new BusinessException(InboxErrorCode.INBOX_LABEL_LIMIT_EXCEEDED));

            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "要返信", "color", "#2563EB",
                    "sourceType", "MENTION", "sourceId", 9));

            mockMvc.perform(post("/api/v1/inbox/labels/suggest-apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_LIMIT_EXCEEDED"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // bulk（Phase 2）
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/inbox/bulk")
    class Bulk {

        @Test
        @DisplayName("正常系: 200・processed/skipped を返す")
        void bulk_200() throws Exception {
            given(bulkService.bulk(eq(USER_ID), any())).willReturn(new BulkResultResponse(2, 1));

            String body = objectMapper.writeValueAsString(Map.of(
                    "action", "ARCHIVE",
                    "items", List.of(
                            Map.of("sourceType", "NOTIFICATION", "sourceId", 1),
                            Map.of("sourceType", "MENTION", "sourceId", 9))));

            mockMvc.perform(post("/api/v1/inbox/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.processed").value(2))
                    .andExpect(jsonPath("$.data.skipped").value(1));
        }

        @Test
        @DisplayName("異常系: items 空 → 400 COMMON_001（@Size min=1）")
        void bulk_emptyItems_400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "action", "ARCHIVE", "items", List.of()));

            mockMvc.perform(post("/api/v1/inbox/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("異常系: action 欠落 → 400 COMMON_001（@NotNull）")
        void bulk_missingAction_400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "items", List.of(Map.of("sourceType", "NOTIFICATION", "sourceId", 1))));

            mockMvc.perform(post("/api/v1/inbox/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }
}
