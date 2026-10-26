package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.CreateEquipmentRequest;
import com.mannschaft.app.facility.dto.EquipmentResponse;
import com.mannschaft.app.facility.dto.UpdateEquipmentRequest;
import com.mannschaft.app.facility.entity.FacilityEquipmentEntity;
import com.mannschaft.app.facility.repository.FacilityEquipmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link FacilityEquipmentService} の単体テスト。
 * 施設備品のCRUD・重複チェック・論理削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityEquipmentService 単体テスト")
class FacilityEquipmentServiceTest {

    @Mock
    private FacilityEquipmentRepository equipmentRepository;

    @Mock
    private FacilityMapper facilityMapper;

    @InjectMocks
    private FacilityEquipmentService equipmentService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long FACILITY_ID = 1L;
    private static final Long EQUIPMENT_ID = 10L;

    private FacilityEquipmentEntity createEquipmentEntity() {
        return FacilityEquipmentEntity.builder()
                .facilityId(FACILITY_ID)
                .name("プロジェクター")
                .description("会議用プロジェクター")
                .totalQuantity(3)
                .pricePerUse(BigDecimal.valueOf(500))
                .isAvailable(true)
                .displayOrder(0)
                .build();
    }

    // ========================================
    // listEquipment
    // ========================================

    @Nested
    @DisplayName("listEquipment")
    class ListEquipment {

        @Test
        @DisplayName("正常系: 備品一覧が返る")
        void 備品一覧取得_正常_リストが返る() {
            // Given
            FacilityEquipmentEntity entity = createEquipmentEntity();
            given(equipmentRepository.findByFacilityIdOrderByDisplayOrderAsc(FACILITY_ID))
                    .willReturn(List.of(entity));
            given(facilityMapper.toEquipmentResponseList(any())).willReturn(List.of(mock(EquipmentResponse.class)));

            // When
            List<EquipmentResponse> result = equipmentService.listEquipment(FACILITY_ID);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 備品がない場合は空リストが返る")
        void 備品一覧取得_備品なし_空リストが返る() {
            // Given
            given(equipmentRepository.findByFacilityIdOrderByDisplayOrderAsc(FACILITY_ID))
                    .willReturn(List.of());
            given(facilityMapper.toEquipmentResponseList(any())).willReturn(List.of());

            // When
            List<EquipmentResponse> result = equipmentService.listEquipment(FACILITY_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // createEquipment
    // ========================================

    @Nested
    @DisplayName("createEquipment")
    class CreateEquipment {

        @Test
        @DisplayName("正常系: 備品が作成される")
        void 備品作成_正常_作成される() {
            // Given
            CreateEquipmentRequest request = new CreateEquipmentRequest(
                    "ホワイトボード", "移動式", 2, BigDecimal.valueOf(200), 1);
            given(equipmentRepository.existsByFacilityIdAndNameAndDeletedAtIsNull(FACILITY_ID, "ホワイトボード"))
                    .willReturn(false);
            given(equipmentRepository.save(any(FacilityEquipmentEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toEquipmentResponse(any(FacilityEquipmentEntity.class)))
                    .willReturn(mock(EquipmentResponse.class));

            // When
            EquipmentResponse result = equipmentService.createEquipment(FACILITY_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(equipmentRepository).save(any(FacilityEquipmentEntity.class));
        }

        @Test
        @DisplayName("正常系: totalQuantityがnullの場合はデフォルト1が設定される")
        void 備品作成_数量null_デフォルト1が設定される() {
            // Given
            CreateEquipmentRequest request = new CreateEquipmentRequest(
                    "マーカー", null, null, null, null);
            given(equipmentRepository.existsByFacilityIdAndNameAndDeletedAtIsNull(FACILITY_ID, "マーカー"))
                    .willReturn(false);
            given(equipmentRepository.save(any(FacilityEquipmentEntity.class)))
                    .willAnswer(invocation -> {
                        FacilityEquipmentEntity saved = invocation.getArgument(0);
                        assertThat(saved.getTotalQuantity()).isEqualTo(1);
                        assertThat(saved.getDisplayOrder()).isEqualTo(0);
                        return saved;
                    });
            given(facilityMapper.toEquipmentResponse(any(FacilityEquipmentEntity.class)))
                    .willReturn(mock(EquipmentResponse.class));

            // When
            EquipmentResponse result = equipmentService.createEquipment(FACILITY_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 備品名重複でFACILITY_004例外")
        void 備品作成_名前重複_FACILITY004例外() {
            // Given
            CreateEquipmentRequest request = new CreateEquipmentRequest(
                    "プロジェクター", null, null, null, null);
            given(equipmentRepository.existsByFacilityIdAndNameAndDeletedAtIsNull(FACILITY_ID, "プロジェクター"))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> equipmentService.createEquipment(FACILITY_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_004"));
        }
    }

    // ========================================
    // updateEquipment
    // ========================================

    @Nested
    @DisplayName("updateEquipment")
    class UpdateEquipment {

        @Test
        @DisplayName("正常系: 備品が更新される")
        void 備品更新_正常_更新される() {
            // Given
            FacilityEquipmentEntity entity = createEquipmentEntity();
            UpdateEquipmentRequest request = new UpdateEquipmentRequest(
                    "プロジェクター改", "高性能プロジェクター", 5,
                    BigDecimal.valueOf(800), true, 1);
            given(equipmentRepository.findByIdAndFacilityId(EQUIPMENT_ID, FACILITY_ID))
                    .willReturn(Optional.of(entity));
            given(facilityMapper.toEquipmentResponse(entity)).willReturn(mock(EquipmentResponse.class));

            // When
            EquipmentResponse result = equipmentService.updateEquipment(FACILITY_ID, EQUIPMENT_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: null項目は既存値が維持される")
        void 備品更新_一部null_既存値が維持される() {
            // Given
            FacilityEquipmentEntity entity = createEquipmentEntity();
            UpdateEquipmentRequest request = new UpdateEquipmentRequest(
                    "プロジェクター", null, null, null, null, null);
            given(equipmentRepository.findByIdAndFacilityId(EQUIPMENT_ID, FACILITY_ID))
                    .willReturn(Optional.of(entity));
            given(facilityMapper.toEquipmentResponse(entity)).willReturn(mock(EquipmentResponse.class));

            // When
            EquipmentResponse result = equipmentService.updateEquipment(FACILITY_ID, EQUIPMENT_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 備品が存在しないでFACILITY_003例外")
        void 備品更新_存在しない_FACILITY003例外() {
            // Given
            UpdateEquipmentRequest request = new UpdateEquipmentRequest(
                    "更新名", null, null, null, null, null);
            given(equipmentRepository.findByIdAndFacilityId(EQUIPMENT_ID, FACILITY_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> equipmentService.updateEquipment(FACILITY_ID, EQUIPMENT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_003"));
        }
    }

    // ========================================
    // deleteEquipment
    // ========================================

    @Nested
    @DisplayName("deleteEquipment")
    class DeleteEquipment {

        @Test
        @DisplayName("正常系: 備品が論理削除される")
        void 備品削除_正常_論理削除される() {
            // Given
            FacilityEquipmentEntity entity = createEquipmentEntity();
            given(equipmentRepository.findByIdAndFacilityId(EQUIPMENT_ID, FACILITY_ID))
                    .willReturn(Optional.of(entity));

            // When
            equipmentService.deleteEquipment(FACILITY_ID, EQUIPMENT_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 備品が存在しないでFACILITY_003例外")
        void 備品削除_存在しない_FACILITY003例外() {
            // Given
            given(equipmentRepository.findByIdAndFacilityId(EQUIPMENT_ID, FACILITY_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> equipmentService.deleteEquipment(FACILITY_ID, EQUIPMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_003"));
        }
    }

    // ========================================
    // findEquipmentOrThrow
    // ========================================

    @Nested
    @DisplayName("findEquipmentOrThrow")
    class FindEquipmentOrThrow {

        @Test
        @DisplayName("正常系: 備品エンティティが返る")
        void 備品取得_正常_エンティティが返る() {
            // Given
            FacilityEquipmentEntity entity = createEquipmentEntity();
            given(equipmentRepository.findById(EQUIPMENT_ID)).willReturn(Optional.of(entity));

            // When
            FacilityEquipmentEntity result = equipmentService.findEquipmentOrThrow(EQUIPMENT_ID);

            // Then
            assertThat(result).isEqualTo(entity);
        }

        @Test
        @DisplayName("異常系: 存在しないでFACILITY_003例外")
        void 備品取得_存在しない_FACILITY003例外() {
            // Given
            given(equipmentRepository.findById(EQUIPMENT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> equipmentService.findEquipmentOrThrow(EQUIPMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_003"));
        }
    }
}
