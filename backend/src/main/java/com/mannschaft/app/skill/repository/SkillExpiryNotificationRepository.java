package com.mannschaft.app.skill.repository;

import com.mannschaft.app.skill.NotificationType;
import com.mannschaft.app.skill.entity.SkillExpiryNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 資格期限通知送信履歴リポジトリ。
 */
public interface SkillExpiryNotificationRepository extends JpaRepository<SkillExpiryNotificationEntity, Long> {

    /**
     * 指定資格IDと通知種別の送信履歴が存在するか確認する。
     */
    boolean existsByMemberSkillIdAndNotificationType(Long memberSkillId, NotificationType notificationType);
}
