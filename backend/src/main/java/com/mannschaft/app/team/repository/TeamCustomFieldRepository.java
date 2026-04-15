package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamCustomFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * チームカスタムフィールドリポジトリ。
 */
public interface TeamCustomFieldRepository extends JpaRepository<TeamCustomFieldEntity, Long> {

    List<TeamCustomFieldEntity> findByTeamIdOrderByDisplayOrderAsc(Long teamId);

    int countByTeamId(Long teamId);

    void deleteByTeamId(Long teamId);
}
