package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.entity.TeamEnabledModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チーム有効モジュールリポジトリ。
 */
public interface TeamEnabledModuleRepository extends JpaRepository<TeamEnabledModuleEntity, Long> {

    List<TeamEnabledModuleEntity> findByTeamId(Long teamId);

    Optional<TeamEnabledModuleEntity> findByTeamIdAndModuleId(Long teamId, Long moduleId);
}
