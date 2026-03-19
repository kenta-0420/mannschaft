package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamBlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チームブロックリポジトリ。
 */
public interface TeamBlockRepository extends JpaRepository<TeamBlockEntity, Long> {

    boolean existsByTeamIdAndUserId(Long teamId, Long userId);

    Optional<TeamBlockEntity> findByTeamIdAndUserId(Long teamId, Long userId);

    List<TeamBlockEntity> findByTeamId(Long teamId);
}
