package com.mannschaft.app.resident.repository;

import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 居室リポジトリ。
 */
public interface DwellingUnitRepository extends JpaRepository<DwellingUnitEntity, Long> {

    Page<DwellingUnitEntity> findByScopeTypeAndTeamIdOrderByUnitNumberAsc(
            String scopeType, Long teamId, Pageable pageable);

    Page<DwellingUnitEntity> findByScopeTypeAndOrganizationIdOrderByUnitNumberAsc(
            String scopeType, Long organizationId, Pageable pageable);

    boolean existsByTeamIdAndUnitNumber(Long teamId, String unitNumber);

    boolean existsByOrganizationIdAndUnitNumber(Long organizationId, String unitNumber);

    @Query("SELECT du FROM DwellingUnitEntity du WHERE du.id = :id AND du.teamId = :teamId")
    Optional<DwellingUnitEntity> findByIdAndTeamId(@Param("id") Long id, @Param("teamId") Long teamId);

    @Query("SELECT du FROM DwellingUnitEntity du WHERE du.id = :id AND du.organizationId = :orgId")
    Optional<DwellingUnitEntity> findByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);
}
