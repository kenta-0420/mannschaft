package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOfficerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * チーム役員リポジトリ。
 */
public interface TeamOfficerRepository extends JpaRepository<TeamOfficerEntity, Long> {

    List<TeamOfficerEntity> findByTeamIdOrderByDisplayOrderAsc(Long teamId);

    int countByTeamId(Long teamId);

    void deleteByTeamId(Long teamId);
}
