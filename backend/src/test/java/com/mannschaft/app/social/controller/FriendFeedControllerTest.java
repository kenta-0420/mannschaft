package com.mannschaft.app.social.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FriendFeedForwardStatus;
import com.mannschaft.app.social.dto.FriendFeedMeta;
import com.mannschaft.app.social.dto.FriendFeedPost;
import com.mannschaft.app.social.dto.FriendFeedResponse;
import com.mannschaft.app.social.dto.FriendFeedSourceTeam;
import com.mannschaft.app.social.service.FriendFeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FriendFeedController} の MockMvc 結合テスト（F01.5 Phase 2）。
 *
 * <p>
 * 管理者フィード取得エンドポイント {@code GET /api/v1/teams/{teamId}/friend-feed} を検証する。
 * 正常系・権限不足・フィルタパラメータ・ページングを網羅する。
 * </p>
 */
@WebMvcTest(FriendFeedController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FriendFeedController 結合テスト")
class FriendFeedControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long SOURCE_TEAM_ID = 20L;
    private static final Long POST_ID_1 = 100L;
    private static final Long POST_ID_2 = 99L;
    private static final Long FORWARD_ID = 300L;
    private static final Long FOLDER_ID = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FriendFeedService friendFeedService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/friend-feed
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/friend-feed")
    class GetFeed {

        @Test
        @DisplayName("正常系: 200 OK + レスポンス構造検証（転送済み・未転送の混在）")
        void getFeed_正常系_200() throws Exception {
            // 転送済み投稿
            FriendFeedPost post1 = FriendFeedPost.builder()
                    .postId(POST_ID_1)
                    .sourceTeam(FriendFeedSourceTeam.builder()
                            .id(SOURCE_TEAM_ID)
                            .name("フレンドチームA")
                            .build())
                    .content("フレンドからの共有投稿1")
                    .receivedAt("2026-04-15T10:00:00")
                    .forwardStatus(FriendFeedForwardStatus.builder()
                            .isForwarded(true)
                            .forwardId(FORWARD_ID)
                            .forwardedAt("2026-04-15T10:05:00")
                            .build())
                    .build();
            // 未転送投稿
            FriendFeedPost post2 = FriendFeedPost.builder()
                    .postId(POST_ID_2)
                    .sourceTeam(FriendFeedSourceTeam.builder()
                            .id(SOURCE_TEAM_ID)
                            .name("フレンドチームA")
                            .build())
                    .content("フレンドからの共有投稿2")
                    .receivedAt("2026-04-14T10:00:00")
                    .forwardStatus(FriendFeedForwardStatus.builder()
                            .isForwarded(false)
                            .forwardId(null)
                            .forwardedAt(null)
                            .build())
                    .build();

            FriendFeedResponse response = FriendFeedResponse.builder()
                    .data(List.of(post1, post2))
                    .meta(FriendFeedMeta.builder()
                            .nextCursor(null)
                            .limit(20)
                            .hasNext(false)
                            .build())
                    .build();

            given(friendFeedService.getFeed(
                    eq(TEAM_ID), eq(USER_ID),
                    isNull(), isNull(), isNull(), isNull(), eq(20)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{teamId}/friend-feed", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].postId").value(100))
                    .andExpect(jsonPath("$.data[0].sourceTeam.name").value("フレンドチームA"))
                    .andExpect(jsonPath("$.data[0].forwardStatus.isForwarded").value(true))
                    .andExpect(jsonPath("$.data[0].forwardStatus.forwardId").value(300))
                    .andExpect(jsonPath("$.data[1].forwardStatus.isForwarded").value(false))
                    .andExpect(jsonPath("$.data[1].forwardStatus.forwardId").doesNotExist())
                    .andExpect(jsonPath("$.meta.hasNext").value(false))
                    .andExpect(jsonPath("$.meta.limit").value(20));
        }

        @Test
        @DisplayName("権限なし: 403 Forbidden (SOCIAL_105)")
        void getFeed_権限なし_403() throws Exception {
            willThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .given(friendFeedService)
                    .getFeed(eq(TEAM_ID), eq(USER_ID),
                            isNull(), isNull(), isNull(), isNull(), eq(20));

            mockMvc.perform(get("/api/v1/teams/{teamId}/friend-feed", TEAM_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }

        @Test
        @DisplayName("カーソルページング: cursor と limit パラメータが Service に正しく伝搬する")
        void getFeed_カーソルページング_パラメータ伝搬() throws Exception {
            Long cursor = 500L;
            int limit = 10;

            FriendFeedResponse response = FriendFeedResponse.builder()
                    .data(List.of())
                    .meta(FriendFeedMeta.builder()
                            .nextCursor(null)
                            .limit(limit)
                            .hasNext(false)
                            .build())
                    .build();

            given(friendFeedService.getFeed(
                    eq(TEAM_ID), eq(USER_ID),
                    isNull(), isNull(), isNull(), eq(cursor), eq(limit)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{teamId}/friend-feed", TEAM_ID)
                            .param("cursor", cursor.toString())
                            .param("limit", String.valueOf(limit)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.limit").value(limit))
                    .andExpect(jsonPath("$.meta.hasNext").value(false));
        }

        @Test
        @DisplayName("フォルダフィルタ: folderId パラメータが Service に伝搬する")
        void getFeed_フォルダフィルタ_パラメータ伝搬() throws Exception {
            FriendFeedResponse response = FriendFeedResponse.builder()
                    .data(List.of())
                    .meta(FriendFeedMeta.builder()
                            .nextCursor(null)
                            .limit(20)
                            .hasNext(false)
                            .build())
                    .build();

            given(friendFeedService.getFeed(
                    eq(TEAM_ID), eq(USER_ID),
                    eq(FOLDER_ID), isNull(), isNull(), isNull(), eq(20)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{teamId}/friend-feed", TEAM_ID)
                            .param("folderId", FOLDER_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.meta.hasNext").value(false));
        }

        @Test
        @DisplayName("次ページあり: hasNext=true かつ nextCursor が正しく返却される")
        void getFeed_次ページあり_hasNextTrue() throws Exception {
            Long nextCursorValue = POST_ID_2;

            FriendFeedPost post = FriendFeedPost.builder()
                    .postId(POST_ID_1)
                    .sourceTeam(FriendFeedSourceTeam.builder()
                            .id(SOURCE_TEAM_ID)
                            .name("フレンドチームA")
                            .build())
                    .content("共有投稿")
                    .receivedAt("2026-04-15T10:00:00")
                    .forwardStatus(FriendFeedForwardStatus.builder()
                            .isForwarded(false)
                            .build())
                    .build();

            FriendFeedResponse response = FriendFeedResponse.builder()
                    .data(List.of(post))
                    .meta(FriendFeedMeta.builder()
                            .nextCursor(nextCursorValue)
                            .limit(1)
                            .hasNext(true)
                            .build())
                    .build();

            given(friendFeedService.getFeed(
                    eq(TEAM_ID), eq(USER_ID),
                    isNull(), isNull(), isNull(), isNull(), eq(1)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{teamId}/friend-feed", TEAM_ID)
                            .param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.hasNext").value(true))
                    .andExpect(jsonPath("$.meta.nextCursor").value(nextCursorValue))
                    .andExpect(jsonPath("$.meta.limit").value(1));
        }

        @Test
        @DisplayName("転送済みのみフィルタ: forwardedOnly=true が Service に伝搬する")
        void getFeed_転送済みのみフィルタ_パラメータ伝搬() throws Exception {
            FriendFeedResponse response = FriendFeedResponse.builder()
                    .data(List.of())
                    .meta(FriendFeedMeta.builder()
                            .nextCursor(null)
                            .limit(20)
                            .hasNext(false)
                            .build())
                    .build();

            given(friendFeedService.getFeed(
                    eq(TEAM_ID), eq(USER_ID),
                    isNull(), isNull(), eq(true), isNull(), eq(20)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{teamId}/friend-feed", TEAM_ID)
                            .param("forwardedOnly", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }
}
