package com.mannschaft.app.social.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.CreateFolderRequest;
import com.mannschaft.app.social.dto.TeamFriendFolderView;
import com.mannschaft.app.social.dto.UpdateFolderRequest;
import com.mannschaft.app.social.service.TeamFriendFolderService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FriendFolderController} の MockMvc 結合テスト（F01.5 Phase 1）。
 *
 * <p>
 * フォルダ CRUD と、フォルダ⇔フレンドの所属管理 API に対する
 * 正常系・異常系（権限・IDOR・上限・重複）を網羅する。
 * </p>
 */
@WebMvcTest(FriendFolderController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FriendFolderController 結合テスト")
class FriendFolderControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long FOLDER_ID = 100L;
    private static final Long TEAM_FRIEND_ID = 500L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamFriendFolderService folderService;

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

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private TeamFriendFolderView sampleFolder(Long id, String name) {
        return TeamFriendFolderView.builder()
                .id(id)
                .name(name)
                .description("説明")
                .color("#10B981")
                .sortOrder(0)
                .isDefault(false)
                .memberCount(3L)
                .createdAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{id}/friend-folders
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{id}/friend-folders")
    class ListFolders {

        @Test
        @DisplayName("正常系: フォルダ一覧 200 OK (論理削除済みは Service 側で除外済み)")
        void listFolders_正常系_200() throws Exception {
            given(folderService.listFolders(TEAM_ID, USER_ID))
                    .willReturn(List.of(
                            sampleFolder(100L, "中学校"),
                            sampleFolder(101L, "高校")));

            mockMvc.perform(get("/api/v1/teams/{id}/friend-folders", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(100))
                    .andExpect(jsonPath("$.data[0].name").value("中学校"))
                    .andExpect(jsonPath("$.data[1].id").value(101))
                    .andExpect(jsonPath("$.data[1].name").value("高校"));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden (SOCIAL_105)")
        void listFolders_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(folderService).listFolders(TEAM_ID, USER_ID);

            mockMvc.perform(get("/api/v1/teams/{id}/friend-folders", TEAM_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{id}/friend-folders
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{id}/friend-folders")
    class CreateFolder {

        @Test
        @DisplayName("正常系: 201 Created")
        void createFolder_正常系_201() throws Exception {
            given(folderService.createFolder(eq(TEAM_ID), any(CreateFolderRequest.class), eq(USER_ID)))
                    .willReturn(sampleFolder(100L, "中学校"));

            String body = """
                    {
                      "name": "中学校",
                      "description": "説明",
                      "color": "#10B981"
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(100))
                    .andExpect(jsonPath("$.data.name").value("中学校"));
        }

        @Test
        @DisplayName("上限超過: 409 Conflict (SOCIAL_111)")
        void createFolder_上限超過_409() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FOLDER_LIMIT_EXCEEDED))
                    .given(folderService)
                    .createFolder(eq(TEAM_ID), any(CreateFolderRequest.class), eq(USER_ID));

            String body = """
                    {
                      "name": "21番目のフォルダ",
                      "color": "#10B981"
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_111"));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden (SOCIAL_105)")
        void createFolder_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(folderService)
                    .createFolder(eq(TEAM_ID), any(CreateFolderRequest.class), eq(USER_ID));

            String body = """
                    { "name": "foo", "color": "#10B981" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }

        @Test
        @DisplayName("Validation: name 空 → 400")
        void createFolder_バリデーション_400() throws Exception {
            String body = """
                    { "name": "", "color": "#10B981" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Validation: color が HEX 形式違反 → 400")
        void createFolder_color不正_400() throws Exception {
            String body = """
                    { "name": "foo", "color": "red" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PUT /api/v1/teams/{id}/friend-folders/{folderId}
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /api/v1/teams/{id}/friend-folders/{folderId}")
    class UpdateFolder {

        @Test
        @DisplayName("正常系: 200 OK")
        void updateFolder_正常系_200() throws Exception {
            given(folderService.updateFolder(eq(TEAM_ID), eq(FOLDER_ID),
                    any(UpdateFolderRequest.class), eq(USER_ID)))
                    .willReturn(sampleFolder(FOLDER_ID, "中学校（改名）"));

            String body = """
                    {
                      "name": "中学校（改名）",
                      "description": "改名後",
                      "color": "#FF0000",
                      "sortOrder": 2
                    }
                    """;

            mockMvc.perform(put("/api/v1/teams/{id}/friend-folders/{folderId}", TEAM_ID, FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("中学校（改名）"));
        }

        @Test
        @DisplayName("IDOR 防御: 他チームの folderId → 404 Not Found (SOCIAL_110)")
        void updateFolder_IDOR_404() throws Exception {
            Long otherFolderId = 9999L;
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND))
                    .given(folderService)
                    .updateFolder(eq(TEAM_ID), eq(otherFolderId),
                            any(UpdateFolderRequest.class), eq(USER_ID));

            String body = """
                    {
                      "name": "foo",
                      "color": "#10B981",
                      "sortOrder": 0
                    }
                    """;

            mockMvc.perform(put("/api/v1/teams/{id}/friend-folders/{folderId}", TEAM_ID, otherFolderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_110"));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden")
        void updateFolder_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(folderService)
                    .updateFolder(eq(TEAM_ID), eq(FOLDER_ID),
                            any(UpdateFolderRequest.class), eq(USER_ID));

            String body = """
                    { "name": "foo", "color": "#10B981", "sortOrder": 0 }
                    """;

            mockMvc.perform(put("/api/v1/teams/{id}/friend-folders/{folderId}", TEAM_ID, FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE /api/v1/teams/{id}/friend-folders/{folderId}
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/teams/{id}/friend-folders/{folderId}")
    class DeleteFolder {

        @Test
        @DisplayName("正常系: 204 No Content (論理削除)")
        void deleteFolder_正常系_204() throws Exception {
            willDoNothing().given(folderService).deleteFolder(TEAM_ID, FOLDER_ID, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-folders/{folderId}", TEAM_ID, FOLDER_ID))
                    .andExpect(status().isNoContent());

            verify(folderService).deleteFolder(TEAM_ID, FOLDER_ID, USER_ID);
        }

        @Test
        @DisplayName("IDOR 防御: 他チームの folderId → 404 Not Found")
        void deleteFolder_IDOR_404() throws Exception {
            Long otherFolderId = 9999L;
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND))
                    .given(folderService).deleteFolder(TEAM_ID, otherFolderId, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-folders/{folderId}", TEAM_ID, otherFolderId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden")
        void deleteFolder_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(folderService).deleteFolder(TEAM_ID, FOLDER_ID, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-folders/{folderId}", TEAM_ID, FOLDER_ID))
                    .andExpect(status().isForbidden());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{id}/friend-folders/{folderId}/members
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{id}/friend-folders/{folderId}/members")
    class AddMember {

        @Test
        @DisplayName("正常系: 201 Created")
        void addMember_正常系_201() throws Exception {
            willDoNothing().given(folderService)
                    .addMemberToFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            String body = """
                    { "teamFriendId": 500 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders/{folderId}/members",
                            TEAM_ID, FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());

            verify(folderService).addMemberToFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);
        }

        @Test
        @DisplayName("重複追加: 409 Conflict (SOCIAL_112)")
        void addMember_重複_409() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FOLDER_MEMBER_ALREADY_EXISTS))
                    .given(folderService)
                    .addMemberToFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            String body = """
                    { "teamFriendId": 500 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders/{folderId}/members",
                            TEAM_ID, FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_112"));
        }

        @Test
        @DisplayName("フォルダ不在 (IDOR): 404 Not Found (SOCIAL_110)")
        void addMember_フォルダ不在_404() throws Exception {
            Long otherFolderId = 9999L;
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND))
                    .given(folderService)
                    .addMemberToFolder(TEAM_ID, otherFolderId, TEAM_FRIEND_ID, USER_ID);

            String body = """
                    { "teamFriendId": 500 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders/{folderId}/members",
                            TEAM_ID, otherFolderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden")
        void addMember_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(folderService)
                    .addMemberToFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            String body = """
                    { "teamFriendId": 500 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders/{folderId}/members",
                            TEAM_ID, FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Validation: teamFriendId 未指定 → 400")
        void addMember_バリデーション_400() throws Exception {
            String body = """
                    { }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-folders/{folderId}/members",
                            TEAM_ID, FOLDER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE /api/v1/teams/{id}/friend-folders/{folderId}/members/{teamFriendId}
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/teams/{id}/friend-folders/{folderId}/members/{teamFriendId}")
    class RemoveMember {

        @Test
        @DisplayName("正常系: 204 No Content")
        void removeMember_正常系_204() throws Exception {
            willDoNothing().given(folderService)
                    .removeMemberFromFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-folders/{folderId}/members/{teamFriendId}",
                            TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID))
                    .andExpect(status().isNoContent());

            verify(folderService)
                    .removeMemberFromFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);
        }

        @Test
        @DisplayName("メンバー不在: 404 Not Found (SOCIAL_113)")
        void removeMember_メンバー不在_404() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FOLDER_MEMBER_NOT_FOUND))
                    .given(folderService)
                    .removeMemberFromFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-folders/{folderId}/members/{teamFriendId}",
                            TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_113"));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden")
        void removeMember_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(folderService)
                    .removeMemberFromFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-folders/{folderId}/members/{teamFriendId}",
                            TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID))
                    .andExpect(status().isForbidden());
        }
    }
}
