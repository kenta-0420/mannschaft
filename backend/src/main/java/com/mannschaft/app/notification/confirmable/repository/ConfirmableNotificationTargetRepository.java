package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の送信時点の宛先ターゲットリポジトリ。
 */
public interface ConfirmableNotificationTargetRepository
        extends JpaRepository<ConfirmableNotificationTargetEntity, UUID> {

    /**
     * 確認通知IDに紐づくターゲット一覧を取得する。
     *
     * @param confirmableNotificationId 確認通知ID
     * @return ターゲット一覧
     */
    List<ConfirmableNotificationTargetEntity> findByConfirmableNotificationId(Long confirmableNotificationId);
}
