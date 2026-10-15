package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.entity.TeamRoleAliasEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チームロール呼称カスタマイズリポジトリ。
 */
public interface TeamRoleAliasRepository extends JpaRepository<TeamRoleAliasEntity, Long> {

    List<TeamRoleAliasEntity> findByTeamId(Long teamId);

    Optional<TeamRoleAliasEntity> findByTeamIdAndRoleName(Long teamId, String roleName);

    void deleteByTeamIdAndRoleName(Long teamId, String roleName);
}
