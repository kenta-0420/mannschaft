package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 施設利用ルールリポジトリ。
 */
public interface FacilityUsageRuleRepository extends JpaRepository<FacilityUsageRuleEntity, Long> {

    Optional<FacilityUsageRuleEntity> findByFacilityId(Long facilityId);
}
