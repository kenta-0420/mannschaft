package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 共用施設リポジトリ。
 */
public interface SharedFacilityRepository extends JpaRepository<SharedFacilityEntity, Long> {

    Page<SharedFacilityEntity> findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
            String scopeType, Long scopeId, Pageable pageable);

    Page<SharedFacilityEntity> findByScopeTypeAndScopeIdAndIsActiveTrueOrderByDisplayOrderAsc(
            String scopeType, Long scopeId, Pageable pageable);

    Optional<SharedFacilityEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);

    List<SharedFacilityEntity> findByScopeTypeAndScopeIdAndIsActiveTrue(String scopeType, Long scopeId);

    int countByScopeTypeAndScopeId(String scopeType, Long scopeId);

    int countByScopeTypeAndScopeIdAndIsActiveTrue(String scopeType, Long scopeId);

    boolean existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
            String scopeType, Long scopeId, String name);
}
