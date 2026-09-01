package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.PermissionGroupEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * パーミッショングループリポジトリ。
 */
public interface PermissionGroupRepository extends JpaRepository<PermissionGroupEntity, Long> {

    List<PermissionGroupEntity> findByTeamId(Long teamId);

    List<PermissionGroupEntity> findByOrganizationId(Long organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PermissionGroupEntity g where g.id = :id")
    Optional<PermissionGroupEntity> findByIdForUpdate(@Param("id") Long id);

    /** 複数groupを常にID昇順でロックし、mutation間のdeadlockを避ける。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PermissionGroupEntity g where g.id in :ids order by g.id asc")
    List<PermissionGroupEntity> findByIdInForUpdateOrderByIdAsc(@Param("ids") List<Long> ids);
}
