package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.EventType;
import com.mannschaft.app.family.entity.TeamPresenceIconEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * プレゼンスカスタムアイコンリポジトリ。
 */
public interface TeamPresenceIconRepository extends JpaRepository<TeamPresenceIconEntity, Long> {

    List<TeamPresenceIconEntity> findByTeamId(Long teamId);

    Optional<TeamPresenceIconEntity> findByTeamIdAndEventType(Long teamId, EventType eventType);
}
