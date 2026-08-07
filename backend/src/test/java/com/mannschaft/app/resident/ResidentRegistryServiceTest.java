package com.mannschaft.app.resident;

import com.mannschaft.app.common.AccessControlService;
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
    @Mock private AccessControlService accessControlService;
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
            ResidentResponse result = service.create(100L, 1L, req);

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
            assertThatThrownBy(() -> service.create(100L, 1L, new CreateResidentRequest(
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
            DwellingUnitEntity unit = DwellingUnitEntity.builder()
                    .scopeType("TEAM").teamId(1L).unitNumber("101").build();
            given(dwellingUnitRepository.findById(1L)).willReturn(Optional.of(unit));

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
            DwellingUnitEntity unit = DwellingUnitEntity.builder()
                    .scopeType("TEAM").teamId(1L).unitNumber("101").build();
            given(dwellingUnitRepository.findById(1L)).willReturn(Optional.of(unit));

            // When / Then
            assertThatThrownBy(() -> service.moveOut(100L, 1L, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_008"));
        }
    }

    /**
     * 認可根治戦役 Wave6 ロットC: residencestatus ドメインからの越境呼び出しの受け口。
     * モジュラーモノリス原則（ドメイン間は ID 参照＋Service 経由のみ）に従い、
     * Entity を境界の外へ返さず boolean のみを返すことを固定する。
     */
    @Nested
    @DisplayName("isActiveResidentOfOrganization")
    class IsActiveResidentOfOrganization {

        @Test
        @DisplayName("正常系: 当該組織の現役居住者であれば true")
        void 現役居住者は true を返す() {
            ResidentRegistryEntity entity = ResidentRegistryEntity.builder()
                    .dwellingUnitId(1L).userId(100L).lastName("田中").firstName("太郎").build();
            given(residentRepository.findActiveByUserIdAndOrganizationId(100L, 10L))
                    .willReturn(Optional.of(entity));

            assertThat(service.isActiveResidentOfOrganization(100L, 10L)).isTrue();
        }

        @Test
        @DisplayName("異常系: 当該組織の居住者台帳が無ければ false")
        void 非居住者は false を返す() {
            given(residentRepository.findActiveByUserIdAndOrganizationId(999L, 10L))
                    .willReturn(Optional.empty());

            assertThat(service.isActiveResidentOfOrganization(999L, 10L)).isFalse();
        }
    }

    @Nested
    @DisplayName("isResidentRegistryOwnedBy")
    class IsResidentRegistryOwnedBy {

        @Test
        @DisplayName("正常系: 台帳の所有者と一致すれば true")
        void 所有者一致は true を返す() {
            ResidentRegistryEntity entity = ResidentRegistryEntity.builder()
                    .dwellingUnitId(1L).userId(100L).lastName("田中").firstName("太郎").build();
            given(residentRepository.findById(3001L)).willReturn(Optional.of(entity));

            assertThat(service.isResidentRegistryOwnedBy(3001L, 100L)).isTrue();
        }

        @Test
        @DisplayName("異常系: 台帳の所有者が別ユーザーであれば false（BOLA対策）")
        void 所有者不一致は false を返す() {
            ResidentRegistryEntity entity = ResidentRegistryEntity.builder()
                    .dwellingUnitId(1L).userId(100L).lastName("田中").firstName("太郎").build();
            given(residentRepository.findById(3001L)).willReturn(Optional.of(entity));

            assertThat(service.isResidentRegistryOwnedBy(3001L, 999L)).isFalse();
        }

        @Test
        @DisplayName("異常系: 台帳が存在しなければ false（存在秘匿は呼び出し側の責務）")
        void 台帳不在は false を返す() {
            given(residentRepository.findById(9999L)).willReturn(Optional.empty());

            assertThat(service.isResidentRegistryOwnedBy(9999L, 100L)).isFalse();
        }
    }
}
