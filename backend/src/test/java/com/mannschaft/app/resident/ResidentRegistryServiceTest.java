package com.mannschaft.app.resident;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.resident.dto.CreateResidentRequest;
import com.mannschaft.app.resident.dto.ResidentResponse;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import com.mannschaft.app.resident.service.ResidentRegistryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResidentRegistryService 単体テスト")
class ResidentRegistryServiceTest {

    @Mock private ResidentRegistryRepository residentRepository;
    @Mock private DwellingUnitRepository dwellingUnitRepository;
    @Mock private ResidentMapper residentMapper;
    @Mock private EncryptionService encryptionService;
    @InjectMocks private ResidentRegistryService service;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: 居住者が登録される")
        void 登録_正常_保存() {
            // Given
            DwellingUnitEntity unit = DwellingUnitEntity.builder()
                    .scopeType("TEAM").teamId(1L).unitNumber("101").build();
            given(dwellingUnitRepository.findById(1L)).willReturn(Optional.of(unit));
            given(encryptionService.hmac(any())).willReturn("hash");
            given(residentRepository.save(any(ResidentRegistryEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(dwellingUnitRepository.save(any(DwellingUnitEntity.class))).willReturn(unit);
            given(residentMapper.toResidentResponse(any(ResidentRegistryEntity.class)))
                    .willReturn(new ResidentResponse(1L, 1L, null, "OWNER", "田中", "太郎",
                            null, null, null, null, null, null, null, null, false, false, null, null, null, null));

            CreateResidentRequest req = new CreateResidentRequest(
                    null, "OWNER", "田中", "太郎", null, null, null, null, null,
                    LocalDate.now(), null, false, null);

            // When
            ResidentResponse result = service.create(1L, req);

            // Then
            assertThat(result.getLastName()).isEqualTo("田中");
            verify(residentRepository).save(any(ResidentRegistryEntity.class));
        }

        @Test
        @DisplayName("異常系: 居室不在でRESIDENT_001例外")
        void 登録_居室不在_例外() {
            // Given
            given(dwellingUnitRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.create(1L, new CreateResidentRequest(
                    null, "OWNER", "田中", "太郎", null, null, null, null, null,
                    LocalDate.now(), null, false, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_001"));
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("異常系: 既に確認済みでRESIDENT_009例外")
        void 確認_既確認_例外() {
            // Given
            ResidentRegistryEntity entity = ResidentRegistryEntity.builder()
                    .dwellingUnitId(1L).lastName("田中").firstName("太郎").build();
            // isVerifiedをtrueに設定するためリフレクション
            try {
                var field = ResidentRegistryEntity.class.getDeclaredField("isVerified");
                field.setAccessible(true);
                field.set(entity, true);
            } catch (Exception ignored) {}
            given(residentRepository.findById(1L)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.verify(1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_009"));
        }
    }

    @Nested
    @DisplayName("moveOut")
    class MoveOut {

        @Test
        @DisplayName("異常系: 既に退去済みでRESIDENT_008例外")
        void 退去_既退去_例外() {
            // Given
            ResidentRegistryEntity entity = ResidentRegistryEntity.builder()
                    .dwellingUnitId(1L).lastName("田中").firstName("太郎").build();
            try {
                var field = ResidentRegistryEntity.class.getDeclaredField("moveOutDate");
                field.setAccessible(true);
                field.set(entity, LocalDate.now());
            } catch (Exception ignored) {}
            given(residentRepository.findById(1L)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.moveOut(1L, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_008"));
        }
    }
}
