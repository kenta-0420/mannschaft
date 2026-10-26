package com.mannschaft.app.skill.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * スキル・資格承認イベント。管理者が資格を承認したときに発行される。
 */
@Getter
public class SkillVerifiedEvent extends BaseEvent {

    private final Long memberSkillId;
    private final Long userId;
    private final Long verifiedBy;
    private final String skillName;

    public SkillVerifiedEvent(Long memberSkillId, Long userId, Long verifiedBy, String skillName) {
        super();
        this.memberSkillId = memberSkillId;
        this.userId = userId;
        this.verifiedBy = verifiedBy;
        this.skillName = skillName;
    }
}
