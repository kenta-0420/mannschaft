package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 通知種別設定リポジトリ。
 */
public interface NotificationTypePreferenceRepository extends JpaRepository<NotificationTypePreferenceEntity, Long> {

    /**
     * 配信バッチ用: 指定通知種別における複数ユーザーの種別設定を一括取得する
     * （通知 fan-out 抜本改修 P1・配信 N+1 消滅）。
     *
     * <p>従来 dispatch は受信者ごとに {@code findByUserIdAndNotificationType} を発行していた（N+1）。
     * チャンク単位で本メソッドを 1 回引き、Map 化してメモリ参照で種別受信可否を判定する
     * （行が無いユーザーは enum 既定値で補完する）。</p>
     */
    List<NotificationTypePreferenceEntity> findByUserIdInAndNotificationType(
            Collection<Long> userIds, String notificationType);

    /**
     * ユーザーの通知種別設定一覧を取得する。
     */
    List<NotificationTypePreferenceEntity> findByUserId(Long userId);

    /**
     * ユーザーと通知種別で設定を取得する。
     */
    Optional<NotificationTypePreferenceEntity> findByUserIdAndNotificationType(
            Long userId, String notificationType);

    /**
     * ユーザーIDに紐づく通知種別設定をすべて削除する（退会匿名化用）。
     */
    void deleteByUserId(Long userId);
}
