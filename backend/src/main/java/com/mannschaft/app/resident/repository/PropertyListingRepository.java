package com.mannschaft.app.resident.repository;

import com.mannschaft.app.resident.entity.PropertyListingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 物件掲示板リポジトリ。
 */
public interface PropertyListingRepository extends JpaRepository<PropertyListingEntity, Long> {

    @Query("""
            SELECT pl FROM PropertyListingEntity pl
            JOIN DwellingUnitEntity du ON pl.dwellingUnitId = du.id
            WHERE du.teamId = :teamId
              AND (:status IS NULL OR pl.status = :status)
              AND (:listingType IS NULL OR pl.listingType = :listingType)
            ORDER BY pl.createdAt DESC
            """)
    Page<PropertyListingEntity> findByTeamId(
            @Param("teamId") Long teamId,
            @Param("status") String status,
            @Param("listingType") String listingType,
            Pageable pageable);

    @Query("""
            SELECT pl FROM PropertyListingEntity pl
            JOIN DwellingUnitEntity du ON pl.dwellingUnitId = du.id
            WHERE du.organizationId = :orgId
              AND (:status IS NULL OR pl.status = :status)
              AND (:listingType IS NULL OR pl.listingType = :listingType)
            ORDER BY pl.createdAt DESC
            """)
    Page<PropertyListingEntity> findByOrganizationId(
            @Param("orgId") Long orgId,
            @Param("status") String status,
            @Param("listingType") String listingType,
            Pageable pageable);

    @Query("SELECT pl FROM PropertyListingEntity pl " +
           "JOIN DwellingUnitEntity du ON pl.dwellingUnitId = du.id " +
           "WHERE pl.id = :id AND du.teamId = :teamId")
    Optional<PropertyListingEntity> findByIdAndTeamId(@Param("id") Long id, @Param("teamId") Long teamId);

    @Query("SELECT pl FROM PropertyListingEntity pl " +
           "JOIN DwellingUnitEntity du ON pl.dwellingUnitId = du.id " +
           "WHERE pl.id = :id AND du.organizationId = :orgId")
    Optional<PropertyListingEntity> findByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);
}
