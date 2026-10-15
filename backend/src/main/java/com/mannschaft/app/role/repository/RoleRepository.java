package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.RoleEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * ロールリポジトリ。
 */
public interface RoleRepository extends JpaRepository<RoleEntity, Long> {

    Optional<RoleEntity> findByName(String name);

    /** last-admin 保護を行う mutation の、必ず存在する共通直列化点。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RoleEntity r WHERE r.name = :name")
    Optional<RoleEntity> findByNameForUpdate(@Param("name") String name);
}
