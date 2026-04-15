package com.mannschaft.app.social.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.ForwardRequest;
import com.mannschaft.app.social.dto.ForwardResponse;
import com.mannschaft.app.social.dto.ForwardTarget;
import com.mannschaft.app.social.dto.FriendForwardExportListResponse;
import com.mannschaft.app.social.dto.FriendForwardExportView;
import com.mannschaft.app.social.service.FriendContentForwardService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FriendContentForwardController} の MockMvc 結合テスト（F01.5 Phase 1）。
 *
 * <p>
 * 転送実行・取消・逆転送履歴の 3 エンドポイントを網羅する。Phase 1 特有の制約
 * （{@code MEMBER_AND_SUPPORTER} 拒否・{@code share_with_friends=FALSE} 投稿の拒否・
 * フレンド関係成立必須）と、冪等性・IDOR 防御・匿名化を検証する。
 * </p>
 */
@WebMvcTest(FriendContentForwardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FriendContentForwardController 結合テスト")
class FriendContentForwardControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long POST_ID = 200L;
    private static final Long FORWARD_ID = 300L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FriendContentForwardService forwardService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{id}/friend-feed/{postId}/forward
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{id}/friend-feed/{postId}/forward")
    class Forward {

        @Test
        @DisplayName("正常系 (target=MEMBER): 201 Created")
        void forward_MEMBER_201() throws Exception {
            ForwardResponse response = ForwardResponse.builder()
                    .forwardId(FORWARD_ID)
                    .sourcePostId(POST_ID)
                    .forwardedPostId(1000L)
                    .target(ForwardTarget.MEMBER)
                    .forwardedAt(LocalDateTime.of(2026, 4, 15, 10, 0))
                    .build();
            given(forwardService.forward(eq(TEAM_ID), eq(POST_ID),
                    any(ForwardRequest.class), eq(USER_ID)))
                    .willReturn(response);

            String body = """
                    { "target": "MEMBER", "comment": "要確認" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.forwardId").value(300))
                    .andExpect(jsonPath("$.data.target").value("MEMBER"))
                    .andExpect(jsonPath("$.data.forwardedPostId").value(1000));
        }

        @Test
        @DisplayName("Phase 1 制約: target=MEMBER_AND_SUPPORTER → 400 (SOCIAL_125)")
        void forward_SUPPORTER_拒否_400() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FORWARD_SUPPORTER_NOT_ALLOWED))
                    .given(forwardService)
                    .forward(eq(TEAM_ID), eq(POST_ID), any(ForwardRequest.class), eq(USER_ID));

            String body = """
                    { "target": "MEMBER_AND_SUPPORTER" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_125"));
        }

        @Test
        @DisplayName("冪等性: 二重転送 → 409 Conflict (SOCIAL_121)")
        void forward_冪等性_409() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FORWARD_ALREADY_EXISTS))
                    .given(forwardService)
                    .forward(eq(TEAM_ID), eq(POST_ID), any(ForwardRequest.class), eq(USER_ID));

            String body = """
                    { "target": "MEMBER" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_121"));
        }

        @Test
        @DisplayName("share_with_friends=FALSE 投稿 → 400 Bad Request (SOCIAL_123)")
        void forward_共有対象外_400() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FORWARD_NOT_SHARABLE))
                    .given(forwardService)
                    .forward(eq(TEAM_ID), eq(POST_ID), any(ForwardRequest.class), eq(USER_ID));

            String body = """
                    { "target": "MEMBER" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_123"));
        }

        @Test
        @DisplayName("フレンド関係なし: 404 Not Found (SOCIAL_124)")
        void forward_フレンド関係なし_404() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FORWARD_RELATION_NOT_FOUND))
                    .given(forwardService)
                    .forward(eq(TEAM_ID), eq(POST_ID), any(ForwardRequest.class), eq(USER_ID));

            String body = """
                    { "target": "MEMBER" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_124"));
        }

        @Test
        @DisplayName("転送元投稿が存在しない: 404 Not Found (SOCIAL_122)")
        void forward_投稿不在_404() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FORWARD_SOURCE_POST_NOT_FOUND))
                    .given(forwardService)
                    .forward(eq(TEAM_ID), eq(POST_ID), any(ForwardRequest.class), eq(USER_ID));

            String body = """
                    { "target": "MEMBER" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_122"));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden (SOCIAL_105)")
        void forward_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(forwardService)
                    .forward(eq(TEAM_ID), eq(POST_ID), any(ForwardRequest.class), eq(USER_ID));

            String body = """
                    { "target": "MEMBER" }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }

        @Test
        @DisplayName("Validation: target 未指定 → 400")
        void forward_バリデーション_400() throws Exception {
            String body = """
                    { }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", TEAM_ID, POST_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE /api/v1/teams/{id}/friend-feed/forwards/{forwardId}
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/teams/{id}/friend-feed/forwards/{forwardId}")
    class Revoke {

        @Test
        @DisplayName("正常系: 204 No Content")
        void revoke_正常系_204() throws Exception {
            willDoNothing().given(forwardService).revoke(TEAM_ID, FORWARD_ID, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-feed/forwards/{forwardId}",
                            TEAM_ID, FORWARD_ID))
                    .andExpect(status().isNoContent());

            verify(forwardService).revoke(TEAM_ID, FORWARD_ID, USER_ID);
        }

        @Test
        @DisplayName("IDOR 防御: 他チームの forwardId → 404 Not Found (SOCIAL_120)")
        void revoke_IDOR_404() throws Exception {
            Long otherForwardId = 9999L;
            willThrow(new BusinessException(SocialErrorCode.FRIEND_FORWARD_NOT_FOUND))
                    .given(forwardService).revoke(TEAM_ID, otherForwardId, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-feed/forwards/{forwardId}",
                            TEAM_ID, otherForwardId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_120"));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden")
        void revoke_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(forwardService).revoke(TEAM_ID, FORWARD_ID, USER_ID);

            mockMvc.perform(delete("/api/v1/teams/{id}/friend-feed/forwards/{forwardId}",
                            TEAM_ID, FORWARD_ID))
                    .andExpect(status().isForbidden());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{id}/friend-forward-exports
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{id}/friend-forward-exports")
    class ListExports {

        @Test
        @DisplayName("正常系: 公開フレンドは実名・非公開は匿名化が反映された状態で返却される")
        void listExports_匿名化_200() throws Exception {
            // 公開フレンド (実名)
            FriendForwardExportView v1 = FriendForwardExportView.builder()
                    .forwardId(1L)
                    .sourcePostId(POST_ID)
                    .forwardingTeamId(30L)
                    .forwardingTeamName("公開フレンドチーム")
                    .target(ForwardTarget.MEMBER)
                    .comment(null)
                    .forwardedAt(LocalDateTime.of(2026, 4, 15, 10, 0))
                    .isRevoked(false)
                    .build();
            // 非公開フレンド (Service 側で「匿名チーム」に置換済みと仮定)
            FriendForwardExportView v2 = FriendForwardExportView.builder()
                    .forwardId(2L)
                    .sourcePostId(POST_ID)
                    .forwardingTeamId(40L)
                    .forwardingTeamName("匿名チーム")
                    .target(ForwardTarget.MEMBER)
                    .comment(null)
                    .forwardedAt(LocalDateTime.of(2026, 4, 14, 10, 0))
                    .isRevoked(false)
                    .build();
            FriendForwardExportListResponse response = FriendForwardExportListResponse.builder()
                    .data(List.of(v1, v2))
                    .pagination(FriendForwardExportListResponse.Pagination.builder()
                            .page(0).size(20).totalElements(2L).totalPages(1).hasNext(false).build())
                    .build();
            given(forwardService.listExportedPosts(eq(TEAM_ID), any(), eq(USER_ID)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{id}/friend-forward-exports", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].forwardingTeamName").value("公開フレンドチーム"))
                    .andExpect(jsonPath("$.data[1].forwardingTeamName").value("匿名チーム"))
                    .andExpect(jsonPath("$.pagination.totalElements").value(2));
        }

        @Test
        @DisplayName("ページング: page=1 / size=50 パラメータが伝搬する")
        void listExports_pagination() throws Exception {
            FriendForwardExportListResponse response = FriendForwardExportListResponse.builder()
                    .data(List.of())
                    .pagination(FriendForwardExportListResponse.Pagination.builder()
                            .page(1).size(50).totalElements(0L).totalPages(0).hasNext(false).build())
                    .build();
            given(forwardService.listExportedPosts(eq(TEAM_ID), any(), eq(USER_ID)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{id}/friend-forward-exports", TEAM_ID)
                            .param("page", "1")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagination.page").value(1))
                    .andExpect(jsonPath("$.pagination.size").value(50));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden")
        void listExports_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(forwardService).listExportedPosts(eq(TEAM_ID), any(), eq(USER_ID));

            mockMvc.perform(get("/api/v1/teams/{id}/friend-forward-exports", TEAM_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }
    }
}
