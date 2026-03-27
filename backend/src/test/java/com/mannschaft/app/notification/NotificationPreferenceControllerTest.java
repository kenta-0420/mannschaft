package com.mannschaft.app.notification;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.notification.controller.NotificationPreferenceController;
import com.mannschaft.app.notification.controller.PushSubscriptionController;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.PreferenceUpdateRequest;
import com.mannschaft.app.notification.dto.PushSubscriptionRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import com.mannschaft.app.notification.service.PushSubscriptionService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import com.mannschaft.app.common.SecurityUtils;

/**
 * {@link NotificationPreferenceController} および {@link PushSubscriptionController} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationPreferenceController 単体テスト")
class NotificationPreferenceControllerTest {

    @Mock
    private NotificationPreferenceService preferenceService;

    @InjectMocks
    private NotificationPreferenceController preferenceController;

    private static final Long USER_ID = 1L;

    private PreferenceResponse createPreferenceResponse() {
        return new PreferenceResponse(1L, USER_ID, "TEAM", 5L, true,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private TypePreferenceResponse createTypePreferenceResponse() {
        return new TypePreferenceResponse(1L, USER_ID, "SCHEDULE_REMINDER", true,
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ========================================
    // listPreferences
    // ========================================

    @Nested
    @DisplayName("listPreferences")
    class ListPreferences {

        @Test
        @DisplayName("通知設定一覧取得_正常_200返却")
        void 通知設定一覧取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(preferenceService.listPreferences(USER_ID))
                        .willReturn(List.of(createPreferenceResponse()));

                // When
                ResponseEntity<ApiResponse<List<PreferenceResponse>>> result =
                        preferenceController.listPreferences();

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).hasSize(1);
            }
        }
    }

    // ========================================
    // updatePreference
    // ========================================

    @Nested
    @DisplayName("updatePreference")
    class UpdatePreference {

        @Test
        @DisplayName("通知設定更新_正常_200返却")
        void 通知設定更新_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                PreferenceUpdateRequest request = new PreferenceUpdateRequest("TEAM", 5L, false);
                PreferenceResponse resp = createPreferenceResponse();
                given(preferenceService.updatePreference(USER_ID, request)).willReturn(resp);

                // When
                ResponseEntity<ApiResponse<PreferenceResponse>> result =
                        preferenceController.updatePreference(request);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isNotNull();
            }
        }
    }

    // ========================================
    // listTypePreferences
    // ========================================

    @Nested
    @DisplayName("listTypePreferences")
    class ListTypePreferences {

        @Test
        @DisplayName("通知種別設定一覧取得_正常_200返却")
        void 通知種別設定一覧取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(preferenceService.listTypePreferences(USER_ID))
                        .willReturn(List.of(createTypePreferenceResponse()));

                // When
                ResponseEntity<ApiResponse<List<TypePreferenceResponse>>> result =
                        preferenceController.listTypePreferences();

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).hasSize(1);
            }
        }
    }

    // ========================================
    // bulkUpdateTypePreferences
    // ========================================

    @Nested
    @DisplayName("bulkUpdateTypePreferences")
    class BulkUpdateTypePreferences {

        @Test
        @DisplayName("通知種別設定一括更新_正常_200返却")
        void 通知種別設定一括更新_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                TypePreferenceBulkUpdateRequest request = new TypePreferenceBulkUpdateRequest(
                        List.of(new TypePreferenceBulkUpdateRequest.TypePreferenceEntry("SCHEDULE_REMINDER", true))
                );
                given(preferenceService.bulkUpdateTypePreferences(USER_ID, request))
                        .willReturn(List.of(createTypePreferenceResponse()));

                // When
                ResponseEntity<ApiResponse<List<TypePreferenceResponse>>> result =
                        preferenceController.bulkUpdateTypePreferences(request);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).hasSize(1);
            }
        }
    }

    // ========================================
    // PushSubscriptionController
    // ========================================

    @Nested
    @DisplayName("PushSubscriptionController")
    class PushSubscriptionControllerTests {

        @Mock
        private PushSubscriptionService pushSubscriptionService;

        @InjectMocks
        private PushSubscriptionController pushSubscriptionController;

        @Test
        @DisplayName("プッシュ購読登録_正常_201返却")
        void プッシュ購読登録_正常_201返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                PushSubscriptionRequest request = new PushSubscriptionRequest(
                        "https://fcm.googleapis.com/test", "p256dh", "auth", "Mozilla/5.0"
                );
                PushSubscriptionEntity entity = PushSubscriptionEntity.builder()
                        .userId(USER_ID)
                        .endpoint("https://fcm.googleapis.com/test")
                        .p256dhKey("p256dh")
                        .authKey("auth")
                        .userAgent("Mozilla/5.0")
                        .build();
                given(pushSubscriptionService.subscribe(USER_ID, request)).willReturn(entity);

                // When
                ResponseEntity<ApiResponse<Long>> result =
                        pushSubscriptionController.subscribe(request);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }

        @Test
        @DisplayName("プッシュ購読解除_正常_204返却")
        void プッシュ購読解除_正常_204返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                String endpoint = "https://fcm.googleapis.com/test";

                // When
                ResponseEntity<Void> result =
                        pushSubscriptionController.unsubscribe(endpoint);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                verify(pushSubscriptionService).unsubscribe(USER_ID, endpoint);
            }
        }
    }
}
