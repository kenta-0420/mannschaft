package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * F04.9 確認通知設定リポジトリ。
 */
public interface ConfirmableNotificationSettingsRepository
        extends JpaRepository<ConfirmableNotificationSettingsEntity, Long> {

    /**
     * スコープ種別とスコープIDで設定を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 設定（未設定の場合 empty）
     */
    Optional<ConfirmableNotificationSettingsEntity> findByScopeTypeAndScopeId(ScopeType scopeType, Long scopeId);
}
