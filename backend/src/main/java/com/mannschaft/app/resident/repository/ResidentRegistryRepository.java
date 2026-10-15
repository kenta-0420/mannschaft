package com.mannschaft.app.resident.repository;

import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 居住者台帳リポジトリ。
 */
public interface ResidentRegistryRepository extends JpaRepository<ResidentRegistryEntity, Long> {

    List<ResidentRegistryEntity> findByDwellingUnitIdOrderByIsPrimaryDescMoveInDateAsc(Long dwellingUnitId);

    @Query("SELECT rr FROM ResidentRegistryEntity rr WHERE rr.userId = :userId AND rr.moveOutDate IS NULL")
    Optional<ResidentRegistryEntity> findActiveByUserId(@Param("userId") Long userId);

    @Query("SELECT rr FROM ResidentRegistryEntity rr " +
           "JOIN DwellingUnitEntity du ON rr.dwellingUnitId = du.id " +
           "WHERE rr.userId = :userId AND rr.moveOutDate IS NULL AND du.teamId = :teamId")
    Optional<ResidentRegistryEntity> findActiveByUserIdAndTeamId(
            @Param("userId") Long userId, @Param("teamId") Long teamId);

    @Query("SELECT rr FROM ResidentRegistryEntity rr " +
           "JOIN DwellingUnitEntity du ON rr.dwellingUnitId = du.id " +
           "WHERE rr.userId = :userId AND rr.moveOutDate IS NULL AND du.organizationId = :orgId")
    Optional<ResidentRegistryEntity> findActiveByUserIdAndOrganizationId(
            @Param("userId") Long userId, @Param("orgId") Long orgId);
}
