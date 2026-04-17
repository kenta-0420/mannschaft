package com.mannschaft.app.social.announcement;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.social.announcement.AnnouncementFeedService.AnnouncementFeedItem;
import com.mannschaft.app.social.announcement.AnnouncementFeedService.AnnouncementFeedResult;
import com.mannschaft.app.social.announcement.controller.AnnouncementFeedController;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * {@link AnnouncementFeedController} の MockMvc 結合テスト（F02.6）。
 *
 * <p>
 * チームお知らせウィジェット API の全 7 エンドポイントを網羅する。
 * {@code @AutoConfigureMockMvc(addFilters = false)} でセキュリティフィルタを無効化し、
 * SecurityContext に認証情報をセットして動作検証する。
 * </p>
 */
@WebMvcTest(AnnouncementFeedController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AnnouncementFeedController 結合テスト")
class AnnouncementFeedControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ANNOUNCEMENT_ID = 100L;
    private static final Long SOURCE_ID = 500L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnnouncementFeedService announcementFeedService;

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

    // ── ヘルパー: テスト用エンティティ生成 ──

    /**
     * テスト用 AnnouncementFeedEntity を生成する。
     */
    private AnnouncementFeedEntity buildAnnouncementEntity(Long id) {
        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.TEAM)
                .scopeId(TEAM_ID)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(SOURCE_ID)
                .authorId(USER_ID)
                .titleCache("テストお知らせタイトル")
                .excerptCache("テスト抜粋テキスト")
                .priority("NORMAL")
                .visibility("MEMBERS_ONLY")
                .build();
        try {
            java.lang.reflect.Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("IDフィールドのセットに失敗しました", e);
        }
        return entity;
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/announcements
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/announcements — 一覧取得")
    class GetAnnouncementFeed {

        @Test
        @DisplayName("正常系: 200 OK + data/meta が返ること")
        void getAnnouncementFeed_正常系_200() throws Exception {
            // Given
            AnnouncementFeedEntity entity = buildAnnouncementEntity(ANNOUNCEMENT_ID);
            AnnouncementFeedItem item = new AnnouncementFeedItem(entity, false);
            AnnouncementFeedResult result = new AnnouncementFeedResult(
                    List.of(item),
                    null,   // nextCursor なし
                    false,  // hasNext = false
                    1L      // unreadCount
            );
            given(announcementFeedService.getAnnouncementFeed(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), eq(USER_ID),
                    anyString(), isNull(), anyInt()))
                    .willReturn(result);

            // When / Then
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].sourceType").value("BLOG_POST"))
                    .andExpect(jsonPath("$.data[0].sourceId").value(SOURCE_ID))
                    .andExpect(jsonPath("$.meta.hasNext").value(false))
                    .andExpect(jsonPath("$.meta.unreadCount").value(1));
        }

        @Test
        @DisplayName("正常系: データなし時は空配列で 200 OK")
        void getAnnouncementFeed_正常系_空リスト() throws Exception {
            // Given
            AnnouncementFeedResult result = new AnnouncementFeedResult(
                    List.of(), null, false, 0L);
            given(announcementFeedService.getAnnouncementFeed(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), eq(USER_ID),
                    anyString(), isNull(), anyInt()))
                    .willReturn(result);

            // When / Then
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0))
                    .andExpect(jsonPath("$.meta.unreadCount").value(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/announcements
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/announcements — お知らせ化")
    class CreateAnnouncement {

        @Test
        @DisplayName("正常系: 201 Created + sourceType/sourceId が処理されること")
        void createAnnouncement_正常系_201() throws Exception {
            // Given
            AnnouncementFeedEntity entity = buildAnnouncementEntity(ANNOUNCEMENT_ID);
            given(announcementFeedService.createAnnouncement(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID),
                    eq(AnnouncementSourceType.BLOG_POST), eq(SOURCE_ID), eq(USER_ID)))
                    .willReturn(entity);

            String requestBody = """
                    {
                        "sourceType": "BLOG_POST",
                        "sourceId": %d
                    }
                    """.formatted(SOURCE_ID);

            // When / Then
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.sourceType").value("BLOG_POST"))
                    .andExpect(jsonPath("$.data.sourceId").value(SOURCE_ID));
        }

        @Test
        @DisplayName("異常系: 権限不足（Service が ANNOUNCE_002 を投げる）→ 400 Bad Request")
        void createAnnouncement_権限不足_400() throws Exception {
            // Given: Service が権限不足例外を投げる
            willThrow(new BusinessException(AnnouncementErrorCode.ANNOUNCE_002))
                    .given(announcementFeedService)
                    .createAnnouncement(any(), anyLong(), any(), anyLong(), anyLong());

            String requestBody = """
                    {
                        "sourceType": "BLOG_POST",
                        "sourceId": %d
                    }
                    """.formatted(SOURCE_ID);

            // When / Then: Severity.WARN → 400 BAD_REQUEST（GlobalExceptionHandler デフォルト）
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_002"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE /api/v1/teams/{teamId}/announcements/{id}
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/teams/{teamId}/announcements/{id} — お知らせ解除")
    class DeleteAnnouncement {

        @Test
        @DisplayName("正常系: 204 No Content")
        void deleteAnnouncement_正常系_204() throws Exception {
            // Given
            willDoNothing().given(announcementFeedService)
                    .deleteAnnouncement(eq(ANNOUNCEMENT_ID), eq(USER_ID));

            // When / Then
            mockMvc.perform(delete("/api/v1/teams/{teamId}/announcements/{id}", TEAM_ID, ANNOUNCEMENT_ID))
                    .andExpect(status().isNoContent());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PATCH /api/v1/teams/{teamId}/announcements/{id}/pin
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /api/v1/teams/{teamId}/announcements/{id}/pin — ピン留めトグル")
    class TogglePin {

        @Test
        @DisplayName("正常系: 200 OK + isPinned フラグが返ること")
        void togglePin_正常系_200() throws Exception {
            // Given: ピン留め後のエンティティを返す
            AnnouncementFeedEntity entity = buildAnnouncementEntity(ANNOUNCEMENT_ID);
            entity.markPinned(USER_ID);
            given(announcementFeedService.togglePin(eq(ANNOUNCEMENT_ID), eq(USER_ID)))
                    .willReturn(entity);

            // When / Then
            mockMvc.perform(patch("/api/v1/teams/{teamId}/announcements/{id}/pin", TEAM_ID, ANNOUNCEMENT_ID)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isPinned").value(true));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/announcements/{id}/read
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/announcements/{id}/read — 既読マーク")
    class MarkAsRead {

        @Test
        @DisplayName("正常系: 200 OK + id と isRead が返ること")
        void markAsRead_正常系_200() throws Exception {
            // Given
            willDoNothing().given(announcementFeedService)
                    .markAsRead(eq(ANNOUNCEMENT_ID), eq(USER_ID));

            // When / Then
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", TEAM_ID, ANNOUNCEMENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(ANNOUNCEMENT_ID))
                    .andExpect(jsonPath("$.data.isRead").value(true));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/announcements/read-all
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/announcements/read-all — 全件既読")
    class MarkAllAsRead {

        @Test
        @DisplayName("正常系: 200 OK + markedCount が返ること")
        void markAllAsRead_正常系_200() throws Exception {
            // Given
            willDoNothing().given(announcementFeedService)
                    .markAllAsRead(eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), eq(USER_ID));

            // When / Then
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.markedCount").value(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 未認証アクセス
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("未認証アクセス — 401 Unauthorized")
    class Unauthorized {

        @Test
        @DisplayName("未認証: SecurityContext をクリアした状態で 401 が返ること")
        void getAnnouncementFeed_未認証_401() throws Exception {
            // Given: SecurityContext をクリア（認証なし）
            SecurityContextHolder.clearContext();

            // When / Then: @PreAuthorize("isAuthenticated()") により 401
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", TEAM_ID))
                    .andExpect(status().isUnauthorized());
        }
    }
}
