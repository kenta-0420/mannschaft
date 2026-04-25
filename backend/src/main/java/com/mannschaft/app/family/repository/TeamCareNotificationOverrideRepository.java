package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.entity.TeamCareNotificationOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * チームケア通知上書き設定リポジトリ。F03.12。
 */
public interface TeamCareNotificationOverrideRepository extends JpaRepository<TeamCareNotificationOverrideEntity, Long> {

    Optional<TeamCareNotificationOverrideEntity> findByScopeTypeAndScopeIdAndCareLinkId(
            String scopeType, Long scopeId, Long careLinkId);
}
