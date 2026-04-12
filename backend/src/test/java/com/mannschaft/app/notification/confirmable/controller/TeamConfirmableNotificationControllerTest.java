package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamConfirmableNotificationController} の単体テスト。
 * HTTP レスポンスコード・レスポンスボディのマッピングを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamConfirmableNotificationController 単体テスト")
class TeamConfirmableNotificationControllerTest {

    @Mock
    private ConfirmableNotificationService notificationService;

    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;

    @Mock
    private ConfirmableNotificationMapper mapper;

    @InjectMocks
    private TeamConfirmableNotificationController controller;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long NOTIFICATION_ID = 100L;

    private ConfirmableNotificationEntity createActiveNotification() {
        return ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID)
                .title("テスト確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .totalRecipientCount(3)
                .build();
    }

    private ConfirmableNotificationResponse createNotificationResponse() {
        return ConfirmableNotificationResponse.builder()
                .id(NOTIFICATION_ID)
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID)
                .title("テスト確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .totalRecipientCount(3)
                .confirmedCount(0L)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private ConfirmableNotificationCreateRequest createValidRequest() {
        ConfirmableNotificationCreateRequest request =
                mock(ConfirmableNotificationCreateRequest.class);
        given(request.getTitle()).willReturn("テスト確認通知");
        given(request.getBody()).willReturn(null);
        given(request.getPriority()).willReturn(ConfirmableNotificationPriority.NORMAL);
        given(request.getDeadlineAt()).willReturn(null);
        given(request.getFirstReminderMinutes()).willReturn(null);
        given(request.getSecondReminderMinutes()).willReturn(null);
        given(request.getActionUrl()).willReturn(null);
        given(request.getTemplateId()).willReturn(null);
        given(request.getRecipientUserIds()).willReturn(List.of(2L, 3L, 4L));
        return request;
    }

    // ========================================
    // send (POST /confirmable-notifications)
    // ========================================

    @Nested
    @DisplayName("send POST /confirmable-notifications")
    class Send {

        @Test
        @DisplayName("POST_confirmable-notifications_正常系_201Createdが返りレスポンスにidが含まれる")
        void POST_confirmableNotifications_正常系_201CreatedとレスポンスにIDが含まれる() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                ConfirmableNotificationCreateRequest request = createValidRequest();
                ConfirmableNotificationEntity entity = createActiveNotification();
                ConfirmableNotificationResponse response = createNotificationResponse();

                given(notificationService.send(
                        eq(ScopeType.TEAM), eq(TEAM_ID), any(), any(), any(), any(),
                        any(), any(), any(), any(), eq(USER_ID), any()))
                        .willReturn(entity);
                given(mapper.toResponse(entity)).willReturn(response);

                // when
                ResponseEntity<ApiResponse<ConfirmableNotificationResponse>> result =
                        controller.send(TEAM_ID, request);

                // then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData().getId()).isEqualTo(NOTIFICATION_ID);
            }
        }
    }

    // ========================================
    // list (GET /confirmable-notifications)
    // ========================================

    @Nested
    @DisplayName("list GET /confirmable-notifications")
    class ListNotifications {

        @Test
        @DisplayName("GET_confirmable-notifications_一覧_200OKとJSON配列が返る")
        void GET_confirmableNotifications_一覧_200OKとJSON配列が返る() {
            // given
            ConfirmableNotificationEntity entity = createActiveNotification();
            ConfirmableNotificationResponse response = createNotificationResponse();

            given(notificationService.listByScope(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(entity));
            given(mapper.toResponse(entity)).willReturn(response);
            given(recipientRepository.countByConfirmableNotificationIdAndIsConfirmedTrue(any()))
                    .willReturn(0L);

            // when
            ResponseEntity<ApiResponse<List<ConfirmableNotificationResponse>>> result =
                    controller.list(TEAM_ID);

            // then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getData()).isNotEmpty();
            assertThat(result.getBody().getData()).hasSize(1);
            verify(notificationService).listByScope(ScopeType.TEAM, TEAM_ID);
        }
    }

    // ========================================
    // cancel (PATCH /{id}/cancel)
    // ========================================

    @Nested
    @DisplayName("cancel PATCH /{id}/cancel")
    class Cancel {

        @Test
        @DisplayName("PATCH_id_cancel_正常系_204NoContentが返る")
        void PATCH_id_cancel_正常系_204NoContentが返る() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                // when
                ResponseEntity<Void> result = controller.cancel(TEAM_ID, NOTIFICATION_ID);

                // then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                verify(notificationService).cancel(NOTIFICATION_ID, USER_ID);
            }
        }
    }

    // ========================================
    // confirm (POST /{id}/confirm)
    // ========================================

    @Nested
    @DisplayName("confirm POST /{id}/confirm")
    class Confirm {

        @Test
        @DisplayName("POST_id_confirm_正常系_204NoContentが返る")
        void POST_id_confirm_正常系_204NoContentが返る() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                // when
                ResponseEntity<Void> result = controller.confirm(TEAM_ID, NOTIFICATION_ID);

                // then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                verify(notificationService).confirm(NOTIFICATION_ID, USER_ID);
            }
        }
    }
}
