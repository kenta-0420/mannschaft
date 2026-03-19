package com.mannschaft.app.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ロールリポジトリ。
 */
public interface RoleRepository extends JpaRepository<RoleEntity, Long> {

    Optional<RoleEntity> findByName(String name);
}
