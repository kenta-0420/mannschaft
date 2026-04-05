package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.entity.FacilityEquipmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 施設備品リポジトリ。
 */
public interface FacilityEquipmentRepository extends JpaRepository<FacilityEquipmentEntity, Long> {

    List<FacilityEquipmentEntity> findByFacilityIdOrderByDisplayOrderAsc(Long facilityId);

    List<FacilityEquipmentEntity> findByFacilityIdAndIsAvailableTrue(Long facilityId);

    Optional<FacilityEquipmentEntity> findByIdAndFacilityId(Long id, Long facilityId);

    boolean existsByFacilityIdAndNameAndDeletedAtIsNull(Long facilityId, String name);
}
