package com.mannschaft.app.family;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * プレゼンスカスタムアイコンリポジトリ。
 */
public interface TeamPresenceIconRepository extends JpaRepository<TeamPresenceIconEntity, Long> {

    /**
     * チームのカスタムアイコン一覧を取得する。
     */
    List<TeamPresenceIconEntity> findByTeamId(Long teamId);

    /**
     * チーム×イベントタイプでアイコンを取得する。
     */
    Optional<TeamPresenceIconEntity> findByTeamIdAndEventType(Long teamId, EventType eventType);
}
