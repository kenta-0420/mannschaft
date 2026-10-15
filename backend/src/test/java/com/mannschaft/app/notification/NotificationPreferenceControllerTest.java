package com.mannschaft.app.notification;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.notification.controller.NotificationPreferenceController;
import com.mannschaft.app.notification.controller.PushSubscriptionController;
import com.mannschaft.app.notification.dto.NotificationSettingsResponse;
import com.mannschaft.app.notification.dto.NotificationSettingsUpdateRequest;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.PreferenceUpdateRequest;
import com.mannschaft.app.notification.dto.PushSubscriptionRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateResponse;
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
 * {@link NotificationPreferenceController} および {@link PushSubscriptionController} の単体テスト
 * （F04.3 ハイブリッド方式）。
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
        return PreferenceResponse.builder()
                .id(1L)
                .userId(USER_ID)
                .scope(new PreferenceResponse.PreferenceScopeDto("TEAM", 5L))
                .scopeName("FCバルセロナ")
                .isEnabled(true)
                .audit(new PreferenceResponse.PreferenceAuditDto(LocalDateTime.now(), LocalDateTime.now()))
                .build();
    }

    private TypePreferenceResponse createTypePreferenceResponse() {
        return TypePreferenceResponse.builder()
                .id(1L)
                .userId(USER_ID)
                .notificationType("SCHEDULE_CREATED")
                .label("スケジュール作成通知")
                .priority("NORMAL")
                .isEnabled(true)
                .channelOverride(false)
                .inAppEnabled(true)
                .pushEnabled(true)
                .isLocked(false)
                .audit(new TypePreferenceResponse.TypePrefAuditDto(LocalDateTime.now(), LocalDateTime.now()))
                .build();
    }

    // ========================================
    // listPreferences
    // ========================================

    @Nested
    @DisplayName("listPreferences")
    class ListPreferences {

        @Test
        @DisplayName("AC-7: 通知設定一覧取得_scopeName含む_200返却")
        void 通知設定一覧取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(preferenceService.listPreferences(USER_ID))
                        .willReturn(List.of(createPreferenceResponse()));

                ResponseEntity<ApiResponse<List<PreferenceResponse>>> result =
                        preferenceController.listPreferences();

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).hasSize(1);
                assertThat(result.getBody().getData().get(0).getScopeName()).isEqualTo("FCバルセロナ");
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
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                PreferenceUpdateRequest request = new PreferenceUpdateRequest("TEAM", 5L, false);
                given(preferenceService.updatePreference(USER_ID, request)).willReturn(createPreferenceResponse());

                ResponseEntity<ApiResponse<PreferenceResponse>> result =
                        preferenceController.updatePreference(request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isNotNull();
            }
        }
    }

    // ========================================
    // listTypePreferences（カタログ・新フィールド）
    // ========================================

    @Nested
    @DisplayName("listTypePreferences")
    class ListTypePreferences {

        @Test
        @DisplayName("AC-1/AC-9: 通知種別設定一覧_新フィールドで返却_category/isMuted不在")
        void 通知種別設定一覧取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(preferenceService.listTypePreferences(USER_ID))
                        .willReturn(List.of(createTypePreferenceResponse()));

                ResponseEntity<ApiResponse<List<TypePreferenceResponse>>> result =
                        preferenceController.listTypePreferences();

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                TypePreferenceResponse dto = result.getBody().getData().get(0);
                assertThat(dto.getLabel()).isNotNull();
                assertThat(dto.getPriority()).isEqualTo("NORMAL");
                assertThat(dto.getChannelOverride()).isFalse();
                assertThat(dto.getInAppEnabled()).isTrue();
                assertThat(dto.getPushEnabled()).isTrue();
                assertThat(dto.getIsLocked()).isFalse();
            }
        }
    }

    // ========================================
    // bulkUpdateTypePreferences（新レスポンス）
    // ========================================

    @Nested
    @DisplayName("bulkUpdateTypePreferences")
    class BulkUpdateTypePreferences {

        @Test
        @DisplayName("AC-3: 通知種別設定一括更新_updated/ignoredLocked返却_200")
        void 通知種別設定一括更新_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                TypePreferenceBulkUpdateRequest request = new TypePreferenceBulkUpdateRequest(
                        List.of(new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(
                                "SCHEDULE_CREATED", false, true, null, null)));
                given(preferenceService.bulkUpdateTypePreferences(USER_ID, request))
                        .willReturn(TypePreferenceBulkUpdateResponse.builder()
                                .updatedCount(1).ignoredLockedCount(0).build());

                ResponseEntity<ApiResponse<TypePreferenceBulkUpdateResponse>> result =
                        preferenceController.bulkUpdateTypePreferences(request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData().getUpdatedCount()).isEqualTo(1);
            }
        }
    }

    // ========================================
    // notification-settings（GET/PUT）
    // ========================================

    @Nested
    @DisplayName("notification-settings")
    class Settings {

        @Test
        @DisplayName("AC-5: グローバル設定取得_200返却")
        void グローバル設定取得_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(preferenceService.getSettings(USER_ID))
                        .willReturn(NotificationSettingsResponse.builder().priorityAutoDelivery(true).build());

                ResponseEntity<ApiResponse<NotificationSettingsResponse>> result =
                        preferenceController.getSettings();

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData().getPriorityAutoDelivery()).isTrue();
            }
        }

        @Test
        @DisplayName("AC-5: グローバル設定更新_200返却")
        void グローバル設定更新_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                NotificationSettingsUpdateRequest request = new NotificationSettingsUpdateRequest(false);
                given(preferenceService.updateSettings(USER_ID, request))
                        .willReturn(NotificationSettingsResponse.builder().priorityAutoDelivery(false).build());

                ResponseEntity<ApiResponse<NotificationSettingsResponse>> result =
                        preferenceController.updateSettings(request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData().getPriorityAutoDelivery()).isFalse();
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

                ResponseEntity<ApiResponse<Long>> result =
                        pushSubscriptionController.subscribe(request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }

        @Test
        @DisplayName("プッシュ購読解除_正常_204返却")
        void プッシュ購読解除_正常_204返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                String endpoint = "https://fcm.googleapis.com/test";

                ResponseEntity<Void> result =
                        pushSubscriptionController.unsubscribe(endpoint);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                verify(pushSubscriptionService).unsubscribe(USER_ID, endpoint);
            }
        }
    }
}
