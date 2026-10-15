package com.mannschaft.app.parking;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.parking.dto.CreateVehicleRequest;
import com.mannschaft.app.parking.dto.VehicleResponse;
import com.mannschaft.app.parking.entity.RegisteredVehicleEntity;
import com.mannschaft.app.parking.repository.RegisteredVehicleRepository;
import com.mannschaft.app.parking.service.RegisteredVehicleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegisteredVehicleService 単体テスト")
class RegisteredVehicleServiceTest {

    @Mock private RegisteredVehicleRepository vehicleRepository;
    @Mock private EncryptionService encryptionService;
    @InjectMocks private RegisteredVehicleService service;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: 車両が登録される")
        void 登録_正常_保存() {
            // Given
            given(encryptionService.hmac(anyString())).willReturn("hash123");
            given(vehicleRepository.findByPlateNumberHash("hash123")).willReturn(Optional.empty());
            given(encryptionService.encryptBytes(any(byte[].class))).willReturn(new byte[]{1});
            given(vehicleRepository.save(any(RegisteredVehicleEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(encryptionService.decryptBytes(any(byte[].class))).willReturn("品川300あ1234".getBytes());

            CreateVehicleRequest req = new CreateVehicleRequest("CAR", "品川300あ1234", "マイカー");

            // When
            VehicleResponse result = service.create(USER_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(vehicleRepository).save(any(RegisteredVehicleEntity.class));
        }

        @Test
        @DisplayName("異常系: ナンバープレート重複でPARKING_021例外")
        void 登録_重複_例外() {
            // Given
            given(encryptionService.hmac(anyString())).willReturn("hash123");
            RegisteredVehicleEntity existing = RegisteredVehicleEntity.builder()
                    .userId(2L).vehicleType(VehicleType.CAR).build();
            given(vehicleRepository.findByPlateNumberHash("hash123")).willReturn(Optional.of(existing));

            CreateVehicleRequest req = new CreateVehicleRequest("CAR", "品川300あ1234", "マイカー");

            // When / Then
            assertThatThrownBy(() -> service.create(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_021"));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("異常系: 車両不在でPARKING_002例外")
        void 削除_不在_例外() {
            // Given
            given(vehicleRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.delete(USER_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_002"));
        }
    }
}
