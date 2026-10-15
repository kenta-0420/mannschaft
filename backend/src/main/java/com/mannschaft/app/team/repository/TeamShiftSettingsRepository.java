package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * チームシフト設定リポジトリ。
 */
public interface TeamShiftSettingsRepository extends JpaRepository<TeamShiftSettingsEntity, Long> {

    Optional<TeamShiftSettingsEntity> findByTeamId(Long teamId);
}
