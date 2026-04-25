package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventCareNotificationLogEntity;
import com.mannschaft.app.family.EventCareNotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * イベントケア通知ログリポジトリ。F03.12 見守り通知の冪等チェックおよびログ管理に使用する。
 */
public interface EventCareNotificationLogRepository extends JpaRepository<EventCareNotificationLogEntity, Long> {

    /**
     * 指定イベント・ケア対象者・通知種別の組み合わせでログが存在するか確認する（冪等チェック用）。
     *
     * @param eventId              イベントID
     * @param careRecipientUserId  ケア対象者のユーザーID
     * @param notificationType     通知種別
     * @return ログが存在する場合 true
     */
    boolean existsByEventIdAndCareRecipientUserIdAndNotificationType(
            Long eventId, Long careRecipientUserId, EventCareNotificationType notificationType);

    /**
     * 指定イベント・ケア対象者のログ一覧を取得する。
     *
     * @param eventId             イベントID
     * @param careRecipientUserId ケア対象者のユーザーID
     * @return ログのリスト
     */
    List<EventCareNotificationLogEntity> findByEventIdAndCareRecipientUserId(
            Long eventId, Long careRecipientUserId);
}
