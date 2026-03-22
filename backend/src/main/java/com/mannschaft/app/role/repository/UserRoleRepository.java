package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.UserRoleEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ユーザー−ロール割当リポジトリ。
 */
public interface UserRoleRepository extends JpaRepository<UserRoleEntity, Long> {

    Optional<UserRoleEntity> findByUserIdAndTeamId(Long userId, Long teamId);

    Optional<UserRoleEntity> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    List<UserRoleEntity> findByTeamIdAndRoleId(Long teamId, Long roleId);

    long countByTeamIdAndRoleId(Long teamId, Long roleId);

    boolean existsByUserIdAndScopeKey(Long userId, String scopeKey);

    long countByOrganizationId(Long organizationId);

    long countByTeamId(Long teamId);

    long countByOrganizationIdAndRoleId(Long organizationId, Long roleId);

    Page<UserRoleEntity> findByOrganizationId(Long organizationId, Pageable pageable);

    Page<UserRoleEntity> findByTeamId(Long teamId, Pageable pageable);

    List<UserRoleEntity> findByUserIdAndTeamIdIsNotNull(Long userId);

    List<UserRoleEntity> findByUserIdAndOrganizationIdIsNotNull(Long userId);

    boolean existsByUserIdAndTeamId(Long userId, Long teamId);

    boolean existsByUserIdAndOrganizationId(Long userId, Long organizationId);

    void deleteByUserIdAndTeamId(Long userId, Long teamId);
}
