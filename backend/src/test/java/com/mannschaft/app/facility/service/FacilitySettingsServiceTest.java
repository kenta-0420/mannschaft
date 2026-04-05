package com.mannschaft.app.facility.service;

import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.FacilitySettingsResponse;
import com.mannschaft.app.facility.dto.UpdateSettingsRequest;
import com.mannschaft.app.facility.entity.FacilitySettingsEntity;
import com.mannschaft.app.facility.repository.FacilitySettingsRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link FacilitySettingsService} の単体テスト。
 * 施設予約設定の取得・更新・デフォルト作成を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilitySettingsService 単体テスト")
class FacilitySettingsServiceTest {

    @Mock
    private FacilitySettingsRepository settingsRepository;

    @Mock
    private FacilityMapper facilityMapper;

    @InjectMocks
    private FacilitySettingsService settingsService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;

    private FacilitySettingsEntity createSettingsEntity() {
        return FacilitySettingsEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .requiresApproval(true)
                .maxBookingsPerDayPerUser(2)
                .allowStripePayment(false)
                .cancellationDeadlineHours(24)
                .noShowPenaltyEnabled(false)
                .noShowPenaltyThreshold(3)
                .noShowPenaltyDays(30)
                .build();
    }

    // ========================================
    // getSettings
    // ========================================

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("正常系: 既存設定が返る")
        void 設定取得_既存_設定が返る() {
            // Given
            FacilitySettingsEntity entity = createSettingsEntity();
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(facilityMapper.toSettingsResponse(entity)).willReturn(mock(FacilitySettingsResponse.class));

            // When
            FacilitySettingsResponse result = settingsService.getSettings(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: 存在しない場合はデフォルト設定が作成される")
        void 設定取得_存在しない_デフォルト作成される() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(settingsRepository.save(any(FacilitySettingsEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toSettingsResponse(any(FacilitySettingsEntity.class)))
                    .willReturn(mock(FacilitySettingsResponse.class));

            // When
            FacilitySettingsResponse result = settingsService.getSettings(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isNotNull();
            verify(settingsRepository).save(any(FacilitySettingsEntity.class));
        }
    }

    // ========================================
    // updateSettings
    // ========================================

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("正常系: 設定が更新される")
        void 設定更新_正常_更新される() {
            // Given
            FacilitySettingsEntity entity = createSettingsEntity();
            UpdateSettingsRequest request = new UpdateSettingsRequest(
                    false, 5, true, 48, true, 5, 60
            );
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(facilityMapper.toSettingsResponse(entity)).willReturn(mock(FacilitySettingsResponse.class));

            // When
            FacilitySettingsResponse result = settingsService.updateSettings(SCOPE_TYPE, SCOPE_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: null項目は既存値が維持される")
        void 設定更新_一部null_既存値が維持される() {
            // Given
            FacilitySettingsEntity entity = createSettingsEntity();
            UpdateSettingsRequest request = new UpdateSettingsRequest(
                    null, null, null, null, null, null, null
            );
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(facilityMapper.toSettingsResponse(entity)).willReturn(mock(FacilitySettingsResponse.class));

            // When
            FacilitySettingsResponse result = settingsService.updateSettings(SCOPE_TYPE, SCOPE_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: 設定が存在しない場合はデフォルト作成後に更新される")
        void 設定更新_存在しない_デフォルト作成後更新される() {
            // Given
            UpdateSettingsRequest request = new UpdateSettingsRequest(
                    false, 3, false, 12, false, 2, 14
            );
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(settingsRepository.save(any(FacilitySettingsEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toSettingsResponse(any(FacilitySettingsEntity.class)))
                    .willReturn(mock(FacilitySettingsResponse.class));

            // When
            FacilitySettingsResponse result = settingsService.updateSettings(SCOPE_TYPE, SCOPE_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(settingsRepository).save(any(FacilitySettingsEntity.class));
        }
    }
}
