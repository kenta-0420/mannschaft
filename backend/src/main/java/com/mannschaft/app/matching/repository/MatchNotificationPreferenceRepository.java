package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.MatchNotificationPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * マッチング推薦通知設定リポジトリ。
 */
public interface MatchNotificationPreferenceRepository extends JpaRepository<MatchNotificationPreferenceEntity, Long> {

    /**
     * チームの通知設定を取得する。
     */
    Optional<MatchNotificationPreferenceEntity> findByTeamId(Long teamId);
}
