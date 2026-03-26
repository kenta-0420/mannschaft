package com.mannschaft.app.notification;

import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.PreferenceUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationPreferenceService} の単体テスト。
 * スコープ別・種別別の通知設定管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationPreferenceService 単体テスト")
class NotificationPreferenceServiceTest {

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @Mock
    private NotificationTypePreferenceRepository typePreferenceRepository;

    @Mock
    private NotificationMapper notificationMapper;

    @InjectMocks
    private NotificationPreferenceService preferenceService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 5L;
    private static final String NOTIFICATION_TYPE = "SCHEDULE_REMINDER";

    private NotificationPreferenceEntity createPreferenceEntity(boolean enabled) {
        return NotificationPreferenceEntity.builder()
                .userId(USER_ID)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .isEnabled(enabled)
                .build();
    }

    private NotificationTypePreferenceEntity createTypePreferenceEntity(boolean enabled) {
        return NotificationTypePreferenceEntity.builder()
                .userId(USER_ID)
                .notificationType(NOTIFICATION_TYPE)
                .isEnabled(enabled)
                .build();
    }

    private PreferenceResponse createPreferenceResponse(boolean enabled) {
        return new PreferenceResponse(1L, USER_ID, SCOPE_TYPE, SCOPE_ID, enabled,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private TypePreferenceResponse createTypePreferenceResponse(boolean enabled) {
        return new TypePreferenceResponse(1L, USER_ID, NOTIFICATION_TYPE, enabled,
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ========================================
    // listPreferences
    // ========================================

    @Nested
    @DisplayName("listPreferences")
    class ListPreferences {

        @Test
        @DisplayName("設定一覧取得_正常_リスト返却")
        void 設定一覧取得_正常_リスト返却() {
            // Given
            NotificationPreferenceEntity entity = createPreferenceEntity(true);
            PreferenceResponse response = createPreferenceResponse(true);

            given(preferenceRepository.findByUserId(USER_ID)).willReturn(List.of(entity));
            given(notificationMapper.toPreferenceResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<PreferenceResponse> result = preferenceService.listPreferences(USER_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("設定一覧取得_設定なし_空リスト返却")
        void 設定一覧取得_設定なし_空リスト返却() {
            // Given
            given(preferenceRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(notificationMapper.toPreferenceResponseList(List.of())).willReturn(List.of());

            // When
            List<PreferenceResponse> result = preferenceService.listPreferences(USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // updatePreference
    // ========================================

    @Nested
    @DisplayName("updatePreference")
    class UpdatePreference {

        @Test
        @DisplayName("設定更新_既存あり_更新して返却")
        void 設定更新_既存あり_更新して返却() {
            // Given
            PreferenceUpdateRequest request = new PreferenceUpdateRequest(SCOPE_TYPE, SCOPE_ID, false);
            NotificationPreferenceEntity entity = createPreferenceEntity(true);
            PreferenceResponse response = createPreferenceResponse(false);

            given(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(preferenceRepository.save(entity)).willReturn(entity);
            given(notificationMapper.toPreferenceResponse(entity)).willReturn(response);

            // When
            PreferenceResponse result = preferenceService.updatePreference(USER_ID, request);

            // Then
            assertThat(result.getIsEnabled()).isFalse();
            verify(preferenceRepository).save(entity);
        }

        @Test
        @DisplayName("設定更新_既存なし_新規作成して返却")
        void 設定更新_既存なし_新規作成して返却() {
            // Given
            PreferenceUpdateRequest request = new PreferenceUpdateRequest(SCOPE_TYPE, SCOPE_ID, true);
            PreferenceResponse response = createPreferenceResponse(true);

            given(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(preferenceRepository.save(any(NotificationPreferenceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(notificationMapper.toPreferenceResponse(any(NotificationPreferenceEntity.class)))
                    .willReturn(response);

            // When
            PreferenceResponse result = preferenceService.updatePreference(USER_ID, request);

            // Then
            assertThat(result.getIsEnabled()).isTrue();
            verify(preferenceRepository).save(any(NotificationPreferenceEntity.class));
        }
    }

    // ========================================
    // listTypePreferences
    // ========================================

    @Nested
    @DisplayName("listTypePreferences")
    class ListTypePreferences {

        @Test
        @DisplayName("種別設定一覧取得_正常_リスト返却")
        void 種別設定一覧取得_正常_リスト返却() {
            // Given
            NotificationTypePreferenceEntity entity = createTypePreferenceEntity(true);
            TypePreferenceResponse response = createTypePreferenceResponse(true);

            given(typePreferenceRepository.findByUserId(USER_ID)).willReturn(List.of(entity));
            given(notificationMapper.toTypePreferenceResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<TypePreferenceResponse> result = preferenceService.listTypePreferences(USER_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("種別設定一覧取得_設定なし_空リスト返却")
        void 種別設定一覧取得_設定なし_空リスト返却() {
            // Given
            given(typePreferenceRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(notificationMapper.toTypePreferenceResponseList(List.of())).willReturn(List.of());

            // When
            List<TypePreferenceResponse> result = preferenceService.listTypePreferences(USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // bulkUpdateTypePreferences
    // ========================================

    @Nested
    @DisplayName("bulkUpdateTypePreferences")
    class BulkUpdateTypePreferences {

        @Test
        @DisplayName("種別設定一括更新_既存あり_更新して返却")
        void 種別設定一括更新_既存あり_更新して返却() {
            // Given
            TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry =
                    new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(NOTIFICATION_TYPE, false);
            TypePreferenceBulkUpdateRequest request = new TypePreferenceBulkUpdateRequest(List.of(entry));
            NotificationTypePreferenceEntity entity = createTypePreferenceEntity(true);
            TypePreferenceResponse response = createTypePreferenceResponse(false);

            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, NOTIFICATION_TYPE))
                    .willReturn(Optional.of(entity));
            given(typePreferenceRepository.save(entity)).willReturn(entity);
            given(notificationMapper.toTypePreferenceResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<TypePreferenceResponse> result = preferenceService.bulkUpdateTypePreferences(USER_ID, request);

            // Then
            assertThat(result).hasSize(1);
            verify(typePreferenceRepository).save(entity);
        }

        @Test
        @DisplayName("種別設定一括更新_既存なし_新規作成して返却")
        void 種別設定一括更新_既存なし_新規作成して返却() {
            // Given
            TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry =
                    new TypePreferenceBulkUpdateRequest.TypePreferenceEntry(NOTIFICATION_TYPE, true);
            TypePreferenceBulkUpdateRequest request = new TypePreferenceBulkUpdateRequest(List.of(entry));
            TypePreferenceResponse response = createTypePreferenceResponse(true);

            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, NOTIFICATION_TYPE))
                    .willReturn(Optional.empty());
            given(typePreferenceRepository.save(any(NotificationTypePreferenceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(notificationMapper.toTypePreferenceResponseList(any()))
                    .willReturn(List.of(response));

            // When
            List<TypePreferenceResponse> result = preferenceService.bulkUpdateTypePreferences(USER_ID, request);

            // Then
            assertThat(result).hasSize(1);
            verify(typePreferenceRepository).save(any(NotificationTypePreferenceEntity.class));
        }

        @Test
        @DisplayName("種別設定一括更新_複数エントリ_全件処理")
        void 種別設定一括更新_複数エントリ_全件処理() {
            // Given
            TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry1 =
                    new TypePreferenceBulkUpdateRequest.TypePreferenceEntry("SCHEDULE_REMINDER", true);
            TypePreferenceBulkUpdateRequest.TypePreferenceEntry entry2 =
                    new TypePreferenceBulkUpdateRequest.TypePreferenceEntry("BULLETIN_POST", false);
            TypePreferenceBulkUpdateRequest request = new TypePreferenceBulkUpdateRequest(List.of(entry1, entry2));

            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "SCHEDULE_REMINDER"))
                    .willReturn(Optional.empty());
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, "BULLETIN_POST"))
                    .willReturn(Optional.empty());
            given(typePreferenceRepository.save(any(NotificationTypePreferenceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(notificationMapper.toTypePreferenceResponseList(any()))
                    .willReturn(List.of(
                            createTypePreferenceResponse(true),
                            createTypePreferenceResponse(false)
                    ));

            // When
            List<TypePreferenceResponse> result = preferenceService.bulkUpdateTypePreferences(USER_ID, request);

            // Then
            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // isNotificationEnabled
    // ========================================

    @Nested
    @DisplayName("isNotificationEnabled")
    class IsNotificationEnabled {

        @Test
        @DisplayName("通知有効判定_設定あり有効_true返却")
        void 通知有効判定_設定あり有効_true返却() {
            // Given
            NotificationPreferenceEntity entity = createPreferenceEntity(true);

            given(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            boolean result = preferenceService.isNotificationEnabled(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("通知有効判定_設定あり無効_false返却")
        void 通知有効判定_設定あり無効_false返却() {
            // Given
            NotificationPreferenceEntity entity = createPreferenceEntity(false);

            given(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            boolean result = preferenceService.isNotificationEnabled(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("通知有効判定_設定なし_デフォルトtrue返却")
        void 通知有効判定_設定なし_デフォルトtrue返却() {
            // Given
            given(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When
            boolean result = preferenceService.isNotificationEnabled(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isTrue();
        }
    }

    // ========================================
    // isTypeEnabled
    // ========================================

    @Nested
    @DisplayName("isTypeEnabled")
    class IsTypeEnabled {

        @Test
        @DisplayName("種別有効判定_設定あり有効_true返却")
        void 種別有効判定_設定あり有効_true返却() {
            // Given
            NotificationTypePreferenceEntity entity = createTypePreferenceEntity(true);

            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, NOTIFICATION_TYPE))
                    .willReturn(Optional.of(entity));

            // When
            boolean result = preferenceService.isTypeEnabled(USER_ID, NOTIFICATION_TYPE);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("種別有効判定_設定あり無効_false返却")
        void 種別有効判定_設定あり無効_false返却() {
            // Given
            NotificationTypePreferenceEntity entity = createTypePreferenceEntity(false);

            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, NOTIFICATION_TYPE))
                    .willReturn(Optional.of(entity));

            // When
            boolean result = preferenceService.isTypeEnabled(USER_ID, NOTIFICATION_TYPE);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("種別有効判定_設定なし_デフォルトtrue返却")
        void 種別有効判定_設定なし_デフォルトtrue返却() {
            // Given
            given(typePreferenceRepository.findByUserIdAndNotificationType(USER_ID, NOTIFICATION_TYPE))
                    .willReturn(Optional.empty());

            // When
            boolean result = preferenceService.isTypeEnabled(USER_ID, NOTIFICATION_TYPE);

            // Then
            assertThat(result).isTrue();
        }
    }
}
