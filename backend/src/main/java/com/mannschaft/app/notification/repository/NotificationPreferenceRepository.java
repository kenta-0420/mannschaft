package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 通知設定リポジトリ。
 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, Long> {

    /**
     * ユーザーの通知設定一覧を取得する。
     */
    List<NotificationPreferenceEntity> findByUserId(Long userId);

    /**
     * ユーザー・スコープで通知設定を取得する。
     */
    Optional<NotificationPreferenceEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, String scopeType, Long scopeId);

    /**
     * ユーザーIDとスコープタイプで通知設定一覧を取得する。
     */
    List<NotificationPreferenceEntity> findByUserIdAndScopeType(Long userId, String scopeType);
}
