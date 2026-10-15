package com.mannschaft.app.skill.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.time.LocalDate;

/**
 * スキル・資格期限リマインダーイベント。期限切れ前通知バッチから発行される。
 */
@Getter
public class SkillExpiryReminderEvent extends BaseEvent {

    private final Long memberSkillId;
    private final Long userId;
    private final String skillName;
    private final LocalDate expiresAt;
    /** 通知種別。"DAYS_30" または "DAYS_7"。 */
    private final String notificationType;

    public SkillExpiryReminderEvent(Long memberSkillId, Long userId, String skillName,
                                    LocalDate expiresAt, String notificationType) {
        super();
        this.memberSkillId = memberSkillId;
        this.userId = userId;
        this.skillName = skillName;
        this.expiresAt = expiresAt;
        this.notificationType = notificationType;
    }
}
