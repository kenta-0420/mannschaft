package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 通知種別設定リポジトリ。
 */
public interface NotificationTypePreferenceRepository extends JpaRepository<NotificationTypePreferenceEntity, Long> {

    /**
     * ユーザーの通知種別設定一覧を取得する。
     */
    List<NotificationTypePreferenceEntity> findByUserId(Long userId);

    /**
     * ユーザーと通知種別で設定を取得する。
     */
    Optional<NotificationTypePreferenceEntity> findByUserIdAndNotificationType(
            Long userId, String notificationType);
}
