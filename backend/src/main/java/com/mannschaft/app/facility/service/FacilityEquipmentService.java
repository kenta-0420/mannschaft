package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.FacilityErrorCode;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.CreateEquipmentRequest;
import com.mannschaft.app.facility.dto.EquipmentResponse;
import com.mannschaft.app.facility.dto.UpdateEquipmentRequest;
import com.mannschaft.app.facility.entity.FacilityEquipmentEntity;
import com.mannschaft.app.facility.repository.FacilityEquipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 施設備品管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityEquipmentService {

    private final FacilityEquipmentRepository equipmentRepository;
    private final FacilityMapper facilityMapper;

    /**
     * 施設の備品一覧を取得する。
     */
    public List<EquipmentResponse> listEquipment(Long facilityId) {
        List<FacilityEquipmentEntity> entities = equipmentRepository
                .findByFacilityIdOrderByDisplayOrderAsc(facilityId);
        return facilityMapper.toEquipmentResponseList(entities);
    }

    /**
     * 備品を作成する。
     */
    @Transactional
    public EquipmentResponse createEquipment(Long facilityId, CreateEquipmentRequest request) {
        if (equipmentRepository.existsByFacilityIdAndNameAndDeletedAtIsNull(facilityId, request.getName())) {
            throw new BusinessException(FacilityErrorCode.EQUIPMENT_NAME_DUPLICATE);
        }

        FacilityEquipmentEntity entity = FacilityEquipmentEntity.builder()
                .facilityId(facilityId)
                .name(request.getName())
                .description(request.getDescription())
                .totalQuantity(request.getTotalQuantity() != null ? request.getTotalQuantity() : 1)
                .pricePerUse(request.getPricePerUse())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .build();

        FacilityEquipmentEntity saved = equipmentRepository.save(entity);
        return facilityMapper.toEquipmentResponse(saved);
    }

    /**
     * 備品を更新する。
     */
    @Transactional
    public EquipmentResponse updateEquipment(Long facilityId, Long equipmentId,
                                              UpdateEquipmentRequest request) {
        FacilityEquipmentEntity entity = equipmentRepository.findByIdAndFacilityId(equipmentId, facilityId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.EQUIPMENT_NOT_FOUND));

        entity.update(
                request.getName(),
                request.getDescription(),
                request.getTotalQuantity() != null ? request.getTotalQuantity() : entity.getTotalQuantity(),
                request.getPricePerUse(),
                request.getIsAvailable() != null ? request.getIsAvailable() : entity.getIsAvailable(),
                request.getDisplayOrder() != null ? request.getDisplayOrder() : entity.getDisplayOrder()
        );

        return facilityMapper.toEquipmentResponse(entity);
    }

    /**
     * 備品を削除する（論理削除）。
     */
    @Transactional
    public void deleteEquipment(Long facilityId, Long equipmentId) {
        FacilityEquipmentEntity entity = equipmentRepository.findByIdAndFacilityId(equipmentId, facilityId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.EQUIPMENT_NOT_FOUND));
        entity.softDelete();
    }

    /**
     * 備品エンティティを取得する（内部用）。
     */
    public FacilityEquipmentEntity findEquipmentOrThrow(Long equipmentId) {
        return equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.EQUIPMENT_NOT_FOUND));
    }
}
