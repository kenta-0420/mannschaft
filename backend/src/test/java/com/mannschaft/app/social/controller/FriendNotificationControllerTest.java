package com.mannschaft.app.social.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.social.dto.FriendNotificationDeliveryResponse;
import com.mannschaft.app.social.dto.FriendNotificationSendRequest;
import com.mannschaft.app.social.service.FriendNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FriendNotificationController} の MockMvc 結合テスト（F01.5 Phase 2）。
 *
 * <p>
 * 認証戦略: {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security の
 * フィルタチェインを無効化し、{@link SecurityContextHolder} に直接テスト用の認証情報を
 * セットする。
 * </p>
 */
@WebMvcTest(FriendNotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FriendNotificationController 結合テスト")
class FriendNotificationControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FriendNotificationService friendNotificationService;

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
    // GET /api/v1/teams/{id}/friend-notifications
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{id}/friend-notifications")
    class ListFriendNotifications {

        @Test
        @DisplayName("認証あり・MANAGE_FRIEND_TEAMS 権限あり → 200 OK でページが返る")
        void list_認証あり_200() throws Exception {
            NotificationResponse notif = new NotificationResponse(
                    1L, USER_ID, "FRIEND_ANNOUNCEMENT", "NORMAL",
                    "テスト通知", "本文", "FRIEND_TEAM", TEAM_ID,
                    "FRIEND_TEAM", TARGET_TEAM_ID, null, USER_ID,
                    false, null, null, null, LocalDateTime.of(2026, 4, 17, 10, 0)
            );
            Page<NotificationResponse> page = new PageImpl<>(List.of(notif));

            given(friendNotificationService.listFriendNotifications(eq(TEAM_ID), eq(USER_ID), isNull(), any()))
                    .willReturn(page);

            mockMvc.perform(get("/api/v1/teams/{id}/friend-notifications", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value(1))
                    .andExpect(jsonPath("$.data.content[0].title").value("テスト通知"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("isRead=false パラメータ付き → 200 OK（未読フィルタが Service へ伝搬）")
        void list_isRead_false_200() throws Exception {
            Page<NotificationResponse> emptyPage = Page.empty();

            given(friendNotificationService.listFriendNotifications(eq(TEAM_ID), eq(USER_ID), eq(false), any()))
                    .willReturn(emptyPage);

            mockMvc.perform(get("/api/v1/teams/{id}/friend-notifications", TEAM_ID)
                            .param("isRead", "false"))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{id}/friend-notifications/send
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/teams/{id}/friend-notifications/send")
    class SendFriendNotification {

        @Test
        @DisplayName("TEAMS 指定・正常 → 202 Accepted で DeliveryResponse が返る")
        void send_TEAMS_202() throws Exception {
            FriendNotificationDeliveryResponse response = FriendNotificationDeliveryResponse.builder()
                    .deliveryId("frdl_20260417120000" + "1234")
                    .queuedTeamsCount(2)
                    .queuedAdminsCount(4)
                    .queuedAt(LocalDateTime.of(2026, 4, 17, 12, 0))
                    .build();

            given(friendNotificationService.sendFriendNotification(eq(TEAM_ID), eq(USER_ID), any()))
                    .willReturn(response);

            String body = """
                    {
                      "targetType": "TEAMS",
                      "targetTeamIds": [20, 30],
                      "title": "合同練習のお知らせ",
                      "body": "来週末の合同練習について詳細を共有します"
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-notifications/send", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.deliveryId").value("frdl_202604171200001234"))
                    .andExpect(jsonPath("$.data.queuedTeamsCount").value(2))
                    .andExpect(jsonPath("$.data.queuedAdminsCount").value(4));
        }

        @Test
        @DisplayName("Validation: title 未指定 → 400 Bad Request")
        void send_title未指定_400() throws Exception {
            String body = """
                    {
                      "targetType": "TEAMS",
                      "targetTeamIds": [20]
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-notifications/send", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Validation: targetType 未指定 → 400 Bad Request")
        void send_targetType未指定_400() throws Exception {
            String body = """
                    {
                      "title": "テスト通知"
                    }
                    """;

            mockMvc.perform(post("/api/v1/teams/{id}/friend-notifications/send", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
