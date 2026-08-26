package com.mannschaft.app.scopefolder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.scopefolder.dto.BulkAssignResponse;
import com.mannschaft.app.scopefolder.dto.FolderNotificationSummaryDto;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.service.MyScopeFolderQueryService;
import com.mannschaft.app.scopefolder.service.MyScopeFolderService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F15.2 {@link MyScopeFolderController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみを起動し、Service 層は {@link MockitoBean} で差し替える。
 * HTTP ⇔ Service のマッピング層（パスバリデーション・パラメータ展開・リクエストバリデーション）の挙動を検証する。</p>
 *
 * <p>認証戦略: {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security の
 * フィルタチェインを無効化し、{@link SecurityContextHolder} に直接テスト用の認証情報をセットする。</p>
 */
@WebMvcTest(MyScopeFolderController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MyScopeFolderController 結合テスト")
class MyScopeFolderControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long FOLDER_ID = 10L;
    private static final Long SCOPE_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MyScopeFolderService folderService;

    @MockitoBean
    private MyScopeFolderQueryService folderQueryService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // UserLocaleFilter の依存解決用
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
        // SecurityUtils.getCurrentUserId() が userId を返せるよう、principal に userId を文字列でセット
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/me/scope-folders?scopeType=TEAM
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/me/scope-folders")
    class GetFolders {

        @Test
        @DisplayName("正常系: 200 + フォルダ一覧 JSON が返る（F15.3: notificationUnreadCount 付き）")
        void getFolders_正常系_200() throws Exception {
            ScopeFolderResponse folder = new ScopeFolderResponse(
                    FOLDER_ID, "チームA", "#FF0000", "pi-users", 0, false, List.of(SCOPE_ID), 3L);
            given(folderQueryService.getFoldersWithUnread(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(folder));

            mockMvc.perform(get("/api/v1/me/scope-folders")
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(FOLDER_ID))
                    .andExpect(jsonPath("$.data[0].name").value("チームA"))
                    .andExpect(jsonPath("$.data[0].color").value("#FF0000"))
                    .andExpect(jsonPath("$.data[0].icon").value("pi-users"))
                    .andExpect(jsonPath("$.data[0].sortOrder").value(0))
                    .andExpect(jsonPath("$.data[0].isDefault").value(false))
                    .andExpect(jsonPath("$.data[0].itemScopeIds[0]").value(SCOPE_ID))
                    .andExpect(jsonPath("$.data[0].notificationUnreadCount").value(3));
        }

        @Test
        @DisplayName("正常系: フォルダなしの場合は空配列が返る")
        void getFolders_正常系_空リスト() throws Exception {
            given(folderQueryService.getFoldersWithUnread(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/me/scope-folders")
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/me/scope-folders?scopeType=TEAM
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/me/scope-folders")
    class CreateFolder {

        @Test
        @DisplayName("正常系: 201 Created + 作成されたフォルダが返る")
        void createFolder_正常系_201() throws Exception {
            ScopeFolderResponse created = new ScopeFolderResponse(
                    FOLDER_ID, "新フォルダ", "#FF0000", null, 0, false, List.of(), 0L);
            given(folderService.createFolder(eq(USER_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(created);

            String body = """
                    {
                      "name": "新フォルダ",
                      "color": "#FF0000"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders")
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(FOLDER_ID))
                    .andExpect(jsonPath("$.data.name").value("新フォルダ"))
                    .andExpect(jsonPath("$.data.color").value("#FF0000"));
        }

        @Test
        @DisplayName("バリデーション違反: name が空 → 400 Bad Request")
        void createFolder_バリデーション違反_name空_400() throws Exception {
            String body = """
                    {
                      "name": "",
                      "color": "#FF0000"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders")
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("バリデーション違反: color が #RRGGBB 形式でない → 400 Bad Request")
        void createFolder_バリデーション違反_color不正_400() throws Exception {
            String body = """
                    {
                      "name": "フォルダ名",
                      "color": "red"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders")
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // PUT /api/v1/me/scope-folders/reorder?scopeType=TEAM
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /api/v1/me/scope-folders/reorder")
    class ReorderFolders {

        @Test
        @DisplayName("正常系: 204 No Content")
        void reorderFolders_正常系_204() throws Exception {
            willDoNothing().given(folderService).reorderFolders(eq(USER_ID), eq(ScopeType.TEAM), any());

            String body = """
                    {
                      "orderedIds": [3, 1, 2]
                    }
                    """;

            mockMvc.perform(put("/api/v1/me/scope-folders/reorder")
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());

            verify(folderService).reorderFolders(eq(USER_ID), eq(ScopeType.TEAM), any());
        }

        @Test
        @DisplayName("バリデーション違反: orderedIds が空リスト → 400 Bad Request")
        void reorderFolders_バリデーション違反_空リスト_400() throws Exception {
            String body = """
                    {
                      "orderedIds": []
                    }
                    """;

            mockMvc.perform(put("/api/v1/me/scope-folders/reorder")
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // PUT /api/v1/me/scope-folders/{folderId}
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /api/v1/me/scope-folders/{folderId}")
    class UpdateFolder {

        @Test
        @DisplayName("正常系: 200 OK + 更新後のフォルダが返る")
        void updateFolder_正常系_200() throws Exception {
            ScopeFolderResponse updated = new ScopeFolderResponse(
                    FOLDER_ID, "更新フォルダ", "#00FF00", null, 0, false, List.of(), 0L);
            given(folderService.updateFolder(eq(USER_ID), eq(FOLDER_ID), any()))
                    .willReturn(updated);

            String body = """
                    {
                      "name": "更新フォルダ",
                      "color": "#00FF00"
                    }
                    """;

            mockMvc.perform(put("/api/v1/me/scope-folders/{folderId}", FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(FOLDER_ID))
                    .andExpect(jsonPath("$.data.name").value("更新フォルダ"))
                    .andExpect(jsonPath("$.data.color").value("#00FF00"));
        }

        @Test
        @DisplayName("バリデーション違反: name が空 → 400 Bad Request")
        void updateFolder_バリデーション違反_name空_400() throws Exception {
            String body = """
                    {
                      "name": ""
                    }
                    """;

            mockMvc.perform(put("/api/v1/me/scope-folders/{folderId}", FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // DELETE /api/v1/me/scope-folders/{folderId}
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/me/scope-folders/{folderId}")
    class DeleteFolder {

        @Test
        @DisplayName("正常系: 204 No Content")
        void deleteFolder_正常系_204() throws Exception {
            willDoNothing().given(folderService).deleteFolder(USER_ID, FOLDER_ID);

            mockMvc.perform(delete("/api/v1/me/scope-folders/{folderId}", FOLDER_ID))
                    .andExpect(status().isNoContent());

            verify(folderService).deleteFolder(USER_ID, FOLDER_ID);
        }
    }

    // ════════════════════════════════════════════════
    // POST /api/v1/me/scope-folders/{folderId}/items
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/me/scope-folders/{folderId}/items")
    class AddItem {

        @Test
        @DisplayName("正常系: 200 OK + 更新後のフォルダが返る")
        void addItem_正常系_200() throws Exception {
            ScopeFolderResponse updated = new ScopeFolderResponse(
                    FOLDER_ID, "フォルダ", "#FF0000", null, 0, false, List.of(SCOPE_ID), 0L);
            // 手動追加の入口なので監査区分は MANUAL で委譲される。
            given(folderService.addItemWithAssignedVia(
                    eq(USER_ID), eq(FOLDER_ID), eq(SCOPE_ID), eq(AssignedVia.MANUAL)))
                    .willReturn(updated);

            String body = """
                    {
                      "scopeId": 100
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders/{folderId}/items", FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(FOLDER_ID))
                    .andExpect(jsonPath("$.data.itemScopeIds[0]").value(SCOPE_ID));
        }

        @Test
        @DisplayName("バリデーション違反: scopeId が null → 400 Bad Request")
        void addItem_バリデーション違反_scopeId_null_400() throws Exception {
            String body = """
                    {
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders/{folderId}/items", FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // DELETE /api/v1/me/scope-folders/{folderId}/items/{scopeId}
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/me/scope-folders/{folderId}/items/{scopeId}")
    class RemoveItem {

        @Test
        @DisplayName("正常系: 204 No Content")
        void removeItem_正常系_204() throws Exception {
            willDoNothing().given(folderService).removeItem(USER_ID, FOLDER_ID, SCOPE_ID);

            mockMvc.perform(delete("/api/v1/me/scope-folders/{folderId}/items/{scopeId}",
                            FOLDER_ID, SCOPE_ID))
                    .andExpect(status().isNoContent());

            verify(folderService).removeItem(USER_ID, FOLDER_ID, SCOPE_ID);
        }
    }

    // ════════════════════════════════════════════════
    // F15.3 §5.2.1: GET /api/v1/me/scope-folders/default?scopeType=TEAM
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/me/scope-folders/default (F15.3)")
    class GetDefaultFolder {

        @Test
        @DisplayName("正常系: 200 OK + 未分類フォルダが返る（lazy 生成想定）")
        void getDefaultFolder_正常系_200() throws Exception {
            ScopeFolderResponse defaultFolder = new ScopeFolderResponse(
                    FOLDER_ID, "未分類", null, null, 9999, true, List.of(), 0L);
            given(folderService.findOrCreateDefault(USER_ID, ScopeType.TEAM))
                    .willReturn(defaultFolder);

            mockMvc.perform(get("/api/v1/me/scope-folders/default")
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(FOLDER_ID))
                    .andExpect(jsonPath("$.data.isDefault").value(true))
                    .andExpect(jsonPath("$.data.sortOrder").value(9999))
                    .andExpect(jsonPath("$.data.name").value("未分類"));
            verify(folderService).findOrCreateDefault(USER_ID, ScopeType.TEAM);
        }

        @Test
        @DisplayName("正常系: ORGANIZATION でも取得できる")
        void getDefaultFolder_正常系_ORGANIZATION() throws Exception {
            ScopeFolderResponse defaultFolder = new ScopeFolderResponse(
                    FOLDER_ID, "未分類", null, null, 9999, true, List.of(), 0L);
            given(folderService.findOrCreateDefault(USER_ID, ScopeType.ORGANIZATION))
                    .willReturn(defaultFolder);

            mockMvc.perform(get("/api/v1/me/scope-folders/default")
                            .param("scopeType", "ORGANIZATION"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isDefault").value(true));
        }
    }

    // ════════════════════════════════════════════════
    // F15.3 §5.2.2: POST /api/v1/me/scope-folders/items/bulk-assign
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/me/scope-folders/items/bulk-assign (F15.3)")
    class BulkAssign {

        @Test
        @DisplayName("正常系: 200 OK + 一括振り分け結果が返る")
        void bulkAssign_正常系_200() throws Exception {
            BulkAssignResponse result = new BulkAssignResponse(3, 1, List.of("SCOPE_FOLDER_NOT_MEMBER"));
            given(folderService.bulkAssign(eq(USER_ID), any())).willReturn(result);

            String body = """
                    {
                      "folderId": 10,
                      "scopeIds": [100, 101, 102, 103],
                      "scopeType": "TEAM"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders/items/bulk-assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.assignedCount").value(3))
                    .andExpect(jsonPath("$.data.skippedCount").value(1))
                    .andExpect(jsonPath("$.data.errors[0]").value("SCOPE_FOLDER_NOT_MEMBER"));
            verify(folderService).bulkAssign(eq(USER_ID), any());
        }

        @Test
        @DisplayName("バリデーション違反: scopeIds が空 → 400 Bad Request")
        void bulkAssign_バリデーション違反_scopeIds空_400() throws Exception {
            String body = """
                    {
                      "folderId": 10,
                      "scopeIds": [],
                      "scopeType": "TEAM"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders/items/bulk-assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("バリデーション違反: folderId 欠落 → 400 Bad Request")
        void bulkAssign_バリデーション違反_folderId欠落_400() throws Exception {
            String body = """
                    {
                      "scopeIds": [100],
                      "scopeType": "TEAM"
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/scope-folders/items/bulk-assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // F15.3 §5.2.3: GET /api/v1/me/scope-folders/notifications/summary?scopeType=TEAM
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/me/scope-folders/notifications/summary (F15.3)")
    class GetNotificationSummary {

        @Test
        @DisplayName("正常系: 200 OK + フォルダ別未読件数リストが返る")
        void getNotificationSummary_正常系_200() throws Exception {
            given(folderQueryService.getNotificationSummary(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(
                            new FolderNotificationSummaryDto(10L, 3L),
                            new FolderNotificationSummaryDto(11L, 0L)));

            mockMvc.perform(get("/api/v1/me/scope-folders/notifications/summary")
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].folderId").value(10))
                    .andExpect(jsonPath("$.data[0].unreadCount").value(3))
                    .andExpect(jsonPath("$.data[1].folderId").value(11))
                    .andExpect(jsonPath("$.data[1].unreadCount").value(0));
        }
    }
}
