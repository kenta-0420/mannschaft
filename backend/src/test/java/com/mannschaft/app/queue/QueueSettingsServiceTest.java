package com.mannschaft.app.queue;

import com.mannschaft.app.queue.dto.QueueSettingsRequest;
import com.mannschaft.app.queue.dto.SettingsResponse;
import com.mannschaft.app.queue.entity.QueueSettingsEntity;
import com.mannschaft.app.queue.repository.QueueSettingsRepository;
import com.mannschaft.app.queue.service.QueueSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link QueueSettingsService} の単体テスト。
 * スコープ単位の設定取得・更新を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueSettingsService 単体テスト")
class QueueSettingsServiceTest {

    @Mock
    private QueueSettingsRepository settingsRepository;

    @Mock
    private QueueMapper queueMapper;

    @InjectMocks
    private QueueSettingsService queueSettingsService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SETTINGS_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final QueueScopeType SCOPE_TYPE = QueueScopeType.TEAM;

    private QueueSettingsEntity createSettingsEntity() {
        return QueueSettingsEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .build();
    }

    private SettingsResponse createSettingsResponse() {
        return new SettingsResponse(
                SETTINGS_ID, "TEAM", SCOPE_ID,
                (short) 5, false, (short) 3, (short) 14,
                (short) 1, true, (short) 3, (short) 5,
                false, false
        );
    }

    // ========================================
    // getSettings
    // ========================================

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("設定取得_既存あり_レスポンス返却")
        void 設定取得_既存あり_レスポンス返却() {
            // Given
            QueueSettingsEntity entity = createSettingsEntity();
            SettingsResponse response = createSettingsResponse();

            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(queueMapper.toSettingsResponse(entity)).willReturn(response);

            // When
            SettingsResponse result = queueSettingsService.getSettings(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getNoShowTimeoutMinutes()).isEqualTo((short) 5);
        }

        @Test
        @DisplayName("設定取得_存在しない_デフォルト作成して返却")
        void 設定取得_存在しない_デフォルト作成して返却() {
            // Given
            QueueSettingsEntity defaultEntity = createSettingsEntity();
            SettingsResponse response = createSettingsResponse();

            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(settingsRepository.save(any(QueueSettingsEntity.class))).willReturn(defaultEntity);
            given(queueMapper.toSettingsResponse(defaultEntity)).willReturn(response);

            // When
            SettingsResponse result = queueSettingsService.getSettings(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isNotNull();
            verify(settingsRepository).save(any(QueueSettingsEntity.class));
        }
    }

    // ========================================
    // updateSettings
    // ========================================

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("設定更新_既存あり_更新して返却")
        void 設定更新_既存あり_更新して返却() {
            // Given
            QueueSettingsRequest request = new QueueSettingsRequest(
                    (short) 10, true, (short) 5, (short) 30,
                    (short) 3, false, (short) 5, (short) 10,
                    true, true
            );
            QueueSettingsEntity entity = createSettingsEntity();
            SettingsResponse response = new SettingsResponse(
                    SETTINGS_ID, "TEAM", SCOPE_ID,
                    (short) 10, true, (short) 5, (short) 30,
                    (short) 3, false, (short) 5, (short) 10,
                    true, true
            );

            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(settingsRepository.save(any(QueueSettingsEntity.class))).willReturn(entity);
            given(queueMapper.toSettingsResponse(entity)).willReturn(response);

            // When
            SettingsResponse result = queueSettingsService.updateSettings(SCOPE_TYPE, SCOPE_ID, request);

            // Then
            assertThat(result.getNoShowTimeoutMinutes()).isEqualTo((short) 10);
            assertThat(result.getNoShowPenaltyEnabled()).isTrue();
            verify(settingsRepository).save(any(QueueSettingsEntity.class));
        }

        @Test
        @DisplayName("設定更新_存在しない_デフォルト作成後更新")
        void 設定更新_存在しない_デフォルト作成後更新() {
            // Given
            QueueSettingsRequest request = new QueueSettingsRequest(
                    (short) 10, null, null, null,
                    null, null, null, null, null, null
            );
            QueueSettingsEntity defaultEntity = createSettingsEntity();
            SettingsResponse response = createSettingsResponse();

            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(settingsRepository.save(any(QueueSettingsEntity.class))).willReturn(defaultEntity);
            given(queueMapper.toSettingsResponse(defaultEntity)).willReturn(response);

            // When
            SettingsResponse result = queueSettingsService.updateSettings(SCOPE_TYPE, SCOPE_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("設定更新_部分更新_null項目はデフォルト維持")
        void 設定更新_部分更新_null項目はデフォルト維持() {
            // Given
            QueueSettingsRequest request = new QueueSettingsRequest(
                    null, null, null, null,
                    null, null, null, null, null, null
            );
            QueueSettingsEntity entity = createSettingsEntity();
            SettingsResponse response = createSettingsResponse();

            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(settingsRepository.save(any(QueueSettingsEntity.class))).willReturn(entity);
            given(queueMapper.toSettingsResponse(entity)).willReturn(response);

            // When
            SettingsResponse result = queueSettingsService.updateSettings(SCOPE_TYPE, SCOPE_ID, request);

            // Then
            assertThat(result.getMaxActiveTicketsPerUser()).isEqualTo((short) 1);
        }
    }
}
