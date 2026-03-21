package com.mannschaft.app.queue.repository;

import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.entity.QueueSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 順番待ち設定リポジトリ。
 */
public interface QueueSettingsRepository extends JpaRepository<QueueSettingsEntity, Long> {

    /**
     * スコープ指定で設定を取得する。
     */
    Optional<QueueSettingsEntity> findByScopeTypeAndScopeId(
            QueueScopeType scopeType, Long scopeId);
}
