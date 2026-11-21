package com.mannschaft.app.notification;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.notification.controller.NotificationAdminController;
import com.mannschaft.app.notification.controller.NotificationController;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.NotificationStatsResponse;
import com.mannschaft.app.notification.dto.SnoozeRequest;
import com.mannschaft.app.notification.dto.UnreadCountResponse;
import com.mannschaft.app.notification.service.NotificationListQueryService;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mannschaft.app.common.SecurityUtils;

/**
 * {@link NotificationController} および {@link NotificationAdminController} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationController 単体テスト")
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationListQueryService notificationListQueryService;

    @InjectMocks
    private NotificationController notificationController;

    private static final Long USER_ID = 1L;
    private static final Long NOTIFICATION_ID = 100L;

    private NotificationResponse createNotificationResponse() {
        return NotificationResponse.builder()
                .id(NOTIFICATION_ID)
                .userId(USER_ID)
                .notificationType("SCHEDULE_REMINDER")
                .priority("NORMAL")
                .title("リマインド")
                .body("出欠未回答です")
                .actionUrl("/schedules/10")
                .sourceType("SCHEDULE")
                .sourceId(10L)
                .scopeType("TEAM")
                .scopeId(5L)
                .actorId(2L)
                .isRead(false)
                .readAt(null)
                .channelsSent(null)
                .snoozedUntil(null)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ========================================
    // listNotifications
    // ========================================

    @Nested
    @DisplayName("listNotifications")
    class ListNotifications {

        @Test
        @DisplayName("通知一覧取得_未読条件を適用し不正値を拒否する")
        void 通知一覧取得_未読条件を適用し不正値を拒否する() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                Pageable pageable = PageRequest.of(0, 10);
                NotificationResponse response = createNotificationResponse().toBuilder().isRead(true).build();
                given(notificationListQueryService.listNotifications(USER_ID, null, null, null, pageable))
                        .willReturn(new PageImpl<>(List.of(response), pageable, 1));
                given(notificationListQueryService.listNotifications(USER_ID, null, null, false, pageable))
                        .willReturn(Page.empty(pageable));
                MockMvc mockMvc = MockMvcBuilders.standaloneSetup(notificationController)
                        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                        .build();

                mockMvc.perform(get("/api/v1/notifications").param("size", "10"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].isRead").value(true));

                mockMvc.perform(get("/api/v1/notifications")
                                .param("size", "10")
                                .param("is_read", "false"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isEmpty());

                mockMvc.perform(get("/api/v1/notifications")
                                .param("size", "10")
                                .param("is_read", "invalid"))
                        .andExpect(status().isBadRequest());
                verify(notificationListQueryService).listNotifications(USER_ID, null, null, null, pageable);
                verify(notificationListQueryService).listNotifications(USER_ID, null, null, false, pageable);
            }
        }
    }

    // ========================================
    // getUnreadCount
    // ========================================

    @Nested
    @DisplayName("getUnreadCount")
    class GetUnreadCount {

        @Test
        @DisplayName("未読件数取得_正常_200返却")
        void 未読件数取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(notificationService.getUnreadCount(USER_ID))
                        .willReturn(new UnreadCountResponse(5L));

                // When
                ResponseEntity<ApiResponse<UnreadCountResponse>> result =
                        notificationController.getUnreadCount();

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData().getUnreadCount()).isEqualTo(5L);
            }
        }
    }

    // ========================================
    // markAsRead
    // ========================================

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("既読化_正常_200返却")
        void 既読化_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                NotificationResponse resp = createNotificationResponse();
                given(notificationService.markAsRead(USER_ID, NOTIFICATION_ID)).willReturn(resp);

                // When
                ResponseEntity<ApiResponse<NotificationResponse>> result =
                        notificationController.markAsRead(NOTIFICATION_ID);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isNotNull();
            }
        }
    }

    // ========================================
    // markAsUnread
    // ========================================

    @Nested
    @DisplayName("markAsUnread")
    class MarkAsUnread {

        @Test
        @DisplayName("未読戻し_正常_200返却")
        void 未読戻し_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                NotificationResponse resp = createNotificationResponse();
                given(notificationService.markAsUnread(USER_ID, NOTIFICATION_ID)).willReturn(resp);

                // When
                ResponseEntity<ApiResponse<NotificationResponse>> result =
                        notificationController.markAsUnread(NOTIFICATION_ID);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isNotNull();
            }
        }
    }

    // ========================================
    // snoozeNotification
    // ========================================

    @Nested
    @DisplayName("snoozeNotification")
    class SnoozeNotification {

        @Test
        @DisplayName("スヌーズ_正常_200返却")
        void スヌーズ_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                SnoozeRequest request = new SnoozeRequest(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(1));
                NotificationResponse resp = createNotificationResponse();
                given(notificationService.snoozeNotification(USER_ID, NOTIFICATION_ID, request))
                        .willReturn(resp);

                // When
                ResponseEntity<ApiResponse<NotificationResponse>> result =
                        notificationController.snoozeNotification(NOTIFICATION_ID, request);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isNotNull();
            }
        }
    }

    // ========================================
    // markAllAsRead
    // ========================================

    @Nested
    @DisplayName("markAllAsRead")
    class MarkAllAsRead {

        @Test
        @DisplayName("全件既読_正常_200返却")
        void 全件既読_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(notificationService.markAllAsRead(USER_ID)).willReturn(3);

                // When
                ResponseEntity<ApiResponse<Integer>> result =
                        notificationController.markAllAsRead();

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isEqualTo(3);
            }
        }
    }

    // ========================================
    // NotificationAdminController
    // ========================================

    @Nested
    @DisplayName("NotificationAdminController - getStats")
    class AdminGetStats {

        @Mock
        private NotificationService adminNotificationService;

        // 認可根治 Wave5: getStats は SYSTEM_ADMIN 限定になり AccessControlService を入口で呼ぶ
        @Mock
        private com.mannschaft.app.common.AccessControlService accessControlService;

        @InjectMocks
        private NotificationAdminController adminController;

        @BeforeEach
        void setUpSecurityContext() {
            // 認可根治 Wave5: getStats が SecurityUtils.getCurrentUserId() を参照するため
            // 認証済みコンテキストを用意する（未設定だと COMMON_000 で落ちる）。
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
        }

        @AfterEach
        void clearSecurityContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("統計取得_正常_200返却")
        void 統計取得_正常_200返却() {
            // Given
            NotificationStatsResponse stats = new NotificationStatsResponse(100L, 30L, 70L, 15L);
            given(adminNotificationService.getStats()).willReturn(stats);

            // When
            ResponseEntity<ApiResponse<NotificationStatsResponse>> result =
                    adminController.getStats();

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getTotalNotifications()).isEqualTo(100L);
            assertThat(result.getBody().getData().getUnreadCount()).isEqualTo(30L);
            // SYSTEM_ADMIN 認可が入口で呼ばれていること
            verify(accessControlService).checkSystemAdmin(USER_ID);
        }
    }
}
