package com.mannschaft.app.social.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.dto.PastForwardHandling;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import com.mannschaft.app.social.dto.TeamFriendView;
import com.mannschaft.app.social.service.TeamFriendsService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamFriendsController} の MockMvc 結合テスト（F01.5 Phase 1）。
 *
 * <p>
 * 4 エンドポイントの HTTP ステータスマッピング・権限・IDOR 防御・
 * NOWAIT 競合時の 202 Accepted レスポンス等を網羅する。
 * </p>
 *
 * <p>
 * 認証戦略: {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security の
 * フィルタチェインを無効化し、{@link SecurityContextHolder} に直接テスト用の認証情報を
 * セットする。Service 層は {@link MockitoBean} で差し替え、HTTP ⇔ Service の
 * 薄いマッピング層のみを検証対象とする。
 * </p>
 */
@WebMvcTest(TeamFriendsController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TeamFriendsController 結合テスト")
class TeamFriendsControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamFriendsService teamFriendsService;

    @MockitoBean
    private AccessControlService accessControlService;

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

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{id}/friends/follow
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{id}/friends/follow")
    class Follow {

        @Test
        @DisplayName("片方向フォロー: 201 Created で followId が返る")
        void follow_片方向_201() throws Exception {
            FollowTeamResponse response = FollowTeamResponse.builder()
                    .followId(100L)
                    .followerTeamId(TEAM_ID)
                    .followedTeamId(TARGET_TEAM_ID)
                    .mutual(false)
                    .createdAt(LocalDateTime.of(2026, 4, 15, 10, 0))
                    .build();
            given(teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID)).willReturn(response);

            String body = """
                    { "targetTeamId": 20 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.followId").value(100))
                    .andExpect(jsonPath("$.data.mutual").value(false))
                    .andExpect(jsonPath("$.data.teamFriendId").doesNotExist());
        }

        @Test
        @DisplayName("相互フォロー成立: 201 Created で teamFriendId が返る")
        void follow_相互_201() throws Exception {
            FollowTeamResponse response = FollowTeamResponse.builder()
                    .followId(100L)
                    .followerTeamId(TEAM_ID)
                    .followedTeamId(TARGET_TEAM_ID)
                    .mutual(true)
                    .teamFriendId(500L)
                    .establishedAt(LocalDateTime.of(2026, 4, 15, 10, 0))
                    .isPublic(true)
                    .createdAt(LocalDateTime.of(2026, 4, 15, 10, 0))
                    .build();
            given(teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID)).willReturn(response);

            String body = """
                    { "targetTeamId": 20 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.mutual").value(true))
                    .andExpect(jsonPath("$.data.teamFriendId").value(500))
                    .andExpect(jsonPath("$.data.isPublic").value(true));
        }

        @Test
        @DisplayName("NOWAIT 競合: 202 Accepted で retryAfterSeconds が返る")
        void follow_NOWAIT競合_202() throws Exception {
            FollowTeamResponse response = FollowTeamResponse.builder()
                    .followerTeamId(TEAM_ID)
                    .followedTeamId(TARGET_TEAM_ID)
                    .mutual(false)
                    .retryAfterSeconds(3)
                    .build();
            given(teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID)).willReturn(response);

            String body = """
                    { "targetTeamId": 20 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.retryAfterSeconds").value(3))
                    .andExpect(jsonPath("$.data.followId").doesNotExist());
        }

        @Test
        @DisplayName("自己フォロー: 400 Bad Request (SOCIAL_101)")
        void follow_自己フォロー_400() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW))
                    .given(teamFriendsService).follow(eq(TEAM_ID), eq(TEAM_ID), eq(USER_ID));

            String body = """
                    { "targetTeamId": 10 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_101"));
        }

        @Test
        @DisplayName("権限なし (MEMBER ロール): 403 Forbidden (SOCIAL_105)")
        void follow_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(teamFriendsService).follow(eq(TEAM_ID), eq(TARGET_TEAM_ID), eq(USER_ID));

            String body = """
                    { "targetTeamId": 20 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }

        @Test
        @DisplayName("既フォロー: 409 Conflict (SOCIAL_102)")
        void follow_既フォロー_409() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_ALREADY_FOLLOWING))
                    .given(teamFriendsService).follow(eq(TEAM_ID), eq(TARGET_TEAM_ID), eq(USER_ID));

            String body = """
                    { "targetTeamId": 20 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_102"));
        }

        @Test
        @DisplayName("対象チーム不在: 404 Not Found (SOCIAL_104)")
        void follow_対象不在_404() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND))
                    .given(teamFriendsService).follow(eq(TEAM_ID), eq(TARGET_TEAM_ID), eq(USER_ID));

            String body = """
                    { "targetTeamId": 20 }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_104"));
        }

        @Test
        @DisplayName("Validation: targetTeamId 未指定 → 400")
        void follow_バリデーション_400() throws Exception {
            String body = """
                    { }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friends/follow", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE /api/v1/teams/{id}/friends/follow/{targetTeamId}
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/teams/{id}/friends/follow/{targetTeamId}")
    class Unfollow {

        @Test
        @DisplayName("KEEP モード: 204 No Content (ボディ省略時のデフォルト)")
        void unfollow_KEEP_204() throws Exception {
            willDoNothing().given(teamFriendsService)
                    .unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.KEEP, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friends/follow/{targetTeamId}", TEAM_ID, TARGET_TEAM_ID))
                    .andExpect(status().isNoContent());

            verify(teamFriendsService)
                    .unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.KEEP, USER_ID);
        }

        @Test
        @DisplayName("SOFT_DELETE モード: 204 No Content")
        void unfollow_SOFT_DELETE_204() throws Exception {
            willDoNothing().given(teamFriendsService)
                    .unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.SOFT_DELETE, USER_ID);

            String body = """
                    { "pastForwardHandling": "SOFT_DELETE" }
                    """;

            mockMvc.perform(delete("/api/v1/teams/{id}/friends/follow/{targetTeamId}", TEAM_ID, TARGET_TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());

            verify(teamFriendsService)
                    .unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.SOFT_DELETE, USER_ID);
        }

        @Test
        @DisplayName("ARCHIVE モード: 204 No Content")
        void unfollow_ARCHIVE_204() throws Exception {
            willDoNothing().given(teamFriendsService)
                    .unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.ARCHIVE, USER_ID);

            String body = """
                    { "pastForwardHandling": "ARCHIVE" }
                    """;

            mockMvc.perform(delete("/api/v1/teams/{id}/friends/follow/{targetTeamId}", TEAM_ID, TARGET_TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());

            verify(teamFriendsService)
                    .unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.ARCHIVE, USER_ID);
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden (SOCIAL_105)")
        void unfollow_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(teamFriendsService)
                    .unfollow(eq(TEAM_ID), eq(TARGET_TEAM_ID), any(PastForwardHandling.class), eq(USER_ID));

            mockMvc.perform(delete("/api/v1/teams/{id}/friends/follow/{targetTeamId}", TEAM_ID, TARGET_TEAM_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }

        @Test
        @DisplayName("フォロー関係が存在しない: 404 Not Found (SOCIAL_103)")
        void unfollow_未フォロー_404() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FOLLOW_NOT_FOUND))
                    .given(teamFriendsService)
                    .unfollow(eq(TEAM_ID), eq(TARGET_TEAM_ID), any(PastForwardHandling.class), eq(USER_ID));

            mockMvc.perform(delete("/api/v1/teams/{id}/friends/follow/{targetTeamId}", TEAM_ID, TARGET_TEAM_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_103"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{id}/friends
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{id}/friends")
    class ListFriends {

        @Test
        @DisplayName("一覧取得 (ADMIN 閲覧): 全件返却・publicOnly=false で Service 呼出")
        void listFriends_ADMIN_200() throws Exception {
            given(accessControlService.getRoleName(USER_ID, TEAM_ID, "TEAM"))
                    .willReturn("ADMIN");
            TeamFriendView v1 = TeamFriendView.builder()
                    .teamFriendId(500L)
                    .friendTeamId(TARGET_TEAM_ID)
                    .friendTeamName("フレンドAチーム")
                    .isPublic(true)
                    .establishedAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                    .build();
            TeamFriendListResponse response = TeamFriendListResponse.builder()
                    .data(List.of(v1))
                    .pagination(TeamFriendListResponse.Pagination.builder()
                            .page(0).size(20).totalElements(1L).totalPages(1).hasNext(false).build())
                    .build();
            given(teamFriendsService.listFriendsResponse(eq(TEAM_ID), eq(USER_ID), any(), eq(false)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{id}/friends", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].teamFriendId").value(500))
                    .andExpect(jsonPath("$.data[0].friendTeamName").value("フレンドAチーム"))
                    .andExpect(jsonPath("$.pagination.totalElements").value(1));
        }

        @Test
        @DisplayName("SUPPORTER ロール: publicOnly=true で Service 呼出 (公開フレンドのみ)")
        void listFriends_SUPPORTER_200_publicOnly() throws Exception {
            given(accessControlService.getRoleName(USER_ID, TEAM_ID, "TEAM"))
                    .willReturn("SUPPORTER");
            TeamFriendListResponse response = TeamFriendListResponse.builder()
                    .data(List.of())
                    .pagination(TeamFriendListResponse.Pagination.builder()
                            .page(0).size(20).totalElements(0L).totalPages(0).hasNext(false).build())
                    .build();
            given(teamFriendsService.listFriendsResponse(eq(TEAM_ID), eq(USER_ID), any(), eq(true)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{id}/friends", TEAM_ID))
                    .andExpect(status().isOk());

            // SUPPORTER は publicOnly=true で呼ばれること
            verify(teamFriendsService).listFriendsResponse(eq(TEAM_ID), eq(USER_ID), any(), eq(true));
        }

        @Test
        @DisplayName("ページング: page=2 / size=50 が Service へ伝搬")
        void listFriends_pagination_伝搬() throws Exception {
            given(accessControlService.getRoleName(USER_ID, TEAM_ID, "TEAM"))
                    .willReturn("ADMIN");
            TeamFriendListResponse response = TeamFriendListResponse.builder()
                    .data(List.of())
                    .pagination(TeamFriendListResponse.Pagination.builder()
                            .page(2).size(50).totalElements(0L).totalPages(0).hasNext(false).build())
                    .build();
            given(teamFriendsService.listFriendsResponse(eq(TEAM_ID), eq(USER_ID), any(), anyBoolean()))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{id}/friends", TEAM_ID)
                            .param("page", "2")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagination.page").value(2))
                    .andExpect(jsonPath("$.pagination.size").value(50));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PATCH /api/v1/teams/{id}/friends/{teamFriendId}/visibility
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /api/v1/teams/{id}/friends/{teamFriendId}/visibility")
    class SetVisibility {

        @Test
        @DisplayName("ADMIN: 204 No Content で公開設定を TRUE に変更")
        void setVisibility_ADMIN_204() throws Exception {
            willDoNothing().given(teamFriendsService)
                    .setVisibility(TEAM_ID, 500L, true, USER_ID);

            String body = """
                    { "isPublic": true }
                    """;

            mockMvc.perform(patch("/api/v1/teams/{id}/friends/{teamFriendId}/visibility", TEAM_ID, 500L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());

            verify(teamFriendsService).setVisibility(TEAM_ID, 500L, true, USER_ID);
        }

        @Test
        @DisplayName("DEPUTY_ADMIN: 403 Forbidden (ADMIN のみ実行可能)")
        void setVisibility_DEPUTY_ADMIN_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY))
                    .given(teamFriendsService)
                    .setVisibility(eq(TEAM_ID), eq(500L), anyBoolean(), eq(USER_ID));

            String body = """
                    { "isPublic": true }
                    """;

            mockMvc.perform(patch("/api/v1/teams/{id}/friends/{teamFriendId}/visibility", TEAM_ID, 500L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_107"));
        }

        @Test
        @DisplayName("IDOR 防御: 他チームの teamFriendId を指定 → 404 Not Found")
        void setVisibility_IDOR_404() throws Exception {
            Long otherTeamFriendId = 9999L;
            willThrow(new BusinessException(SocialErrorCode.FRIEND_RELATION_NOT_FOUND))
                    .given(teamFriendsService)
                    .setVisibility(eq(TEAM_ID), eq(otherTeamFriendId), anyBoolean(), eq(USER_ID));

            String body = """
                    { "isPublic": true }
                    """;

            mockMvc.perform(patch("/api/v1/teams/{id}/friends/{teamFriendId}/visibility",
                            TEAM_ID, otherTeamFriendId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_106"));
        }

        @Test
        @DisplayName("Validation: isPublic 未指定 → 400 Bad Request")
        void setVisibility_バリデーション_400() throws Exception {
            String body = """
                    { }
                    """;

            mockMvc.perform(patch("/api/v1/teams/{id}/friends/{teamFriendId}/visibility", TEAM_ID, 500L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
