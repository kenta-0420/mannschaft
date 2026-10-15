package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * F04.3 グローバル通知設定リポジトリ。1ユーザー1行。
 */
public interface NotificationSettingsRepository
        extends JpaRepository<NotificationSettingsEntity, UUID> {

    /**
     * ユーザーのグローバル通知設定を取得する。
     */
    Optional<NotificationSettingsEntity> findByUserId(Long userId);

    /**
     * ユーザーIDに紐づくグローバル通知設定を削除する（退会匿名化用）。
     */
    void deleteByUserId(Long userId);
}
