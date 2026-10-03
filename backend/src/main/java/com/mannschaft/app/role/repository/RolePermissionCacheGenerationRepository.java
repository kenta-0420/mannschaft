package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.RolePermissionCacheGenerationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** role-permissions キャッシュ世代の永続化。 */
public interface RolePermissionCacheGenerationRepository
        extends JpaRepository<RolePermissionCacheGenerationEntity, UUID> {

    @Query("SELECT generation.generation FROM RolePermissionCacheGenerationEntity generation "
            + "WHERE generation.scopeType = :scopeType AND generation.scopeId = :scopeId")
    Optional<Long> findGeneration(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /** 新規行は世代1で作り、既存行は単一SQLで原子的に進める。 */
    @Modifying
    @Query(value = "INSERT INTO role_permission_cache_generations "
            + "(id, scope_type, scope_id, generation, updated_at) "
            + "VALUES (:id, :scopeType, :scopeId, 1, UTC_TIMESTAMP(3)) "
            + "ON DUPLICATE KEY UPDATE generation = generation + 1, updated_at = UTC_TIMESTAMP(3)",
            nativeQuery = true)
    void increment(
            @Param("id") UUID id,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);
}
