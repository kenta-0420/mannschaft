package com.mannschaft.app.parking;

import com.mannschaft.app.parking.dto.ParkingSettingsResponse;
import com.mannschaft.app.parking.dto.UpdateSettingsRequest;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import com.mannschaft.app.parking.repository.ParkingSettingsRepository;
import com.mannschaft.app.parking.service.ParkingSettingsService;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingSettingsService 単体テスト")
class ParkingSettingsServiceTest {

    @Mock private ParkingSettingsRepository settingsRepository;
    @Mock private ParkingMapper parkingMapper;
    @InjectMocks private ParkingSettingsService service;

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("正常系: 設定不在の場合デフォルト値が返却される")
        void 取得_不在_デフォルト返却() {
            // Given
            given(settingsRepository.findByScopeTypeAndScopeId("TEAM", 1L)).willReturn(Optional.empty());
            given(parkingMapper.toSettingsResponse(any(ParkingSettingsEntity.class)))
                    .willReturn(new ParkingSettingsResponse(null, "TEAM", 1L, 1, 3, 14, false));

            // When
            ParkingSettingsResponse result = service.getSettings("TEAM", 1L);

            // Then
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("正常系: 設定が更新される")
        void 更新_正常_保存() {
            // Given
            ParkingSettingsEntity entity = ParkingSettingsEntity.builder()
                    .scopeType("TEAM").scopeId(1L).build();
            given(settingsRepository.findByScopeTypeAndScopeId("TEAM", 1L))
                    .willReturn(Optional.of(entity));
            given(settingsRepository.save(any(ParkingSettingsEntity.class))).willReturn(entity);
            given(parkingMapper.toSettingsResponse(any(ParkingSettingsEntity.class)))
                    .willReturn(new ParkingSettingsResponse(1L, "TEAM", 1L, 2, 5, 30, true));

            UpdateSettingsRequest req = new UpdateSettingsRequest(2, 5, 30, true);

            // When
            ParkingSettingsResponse result = service.updateSettings("TEAM", 1L, req);

            // Then
            assertThat(result).isNotNull();
            verify(settingsRepository).save(any(ParkingSettingsEntity.class));
        }
    }
}
