package com.mannschaft.app.skill.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * スキル・資格登録イベント。メンバーが新規に資格を登録したときに発行される。
 */
@Getter
public class SkillRegisteredEvent extends BaseEvent {

    private final Long memberSkillId;
    private final Long userId;
    private final Long categoryId;
    private final String categoryName;
    private final String skillName;
    private final String scopeType;
    private final Long scopeId;

    public SkillRegisteredEvent(Long memberSkillId, Long userId, Long categoryId,
                                String categoryName, String skillName,
                                String scopeType, Long scopeId) {
        super();
        this.memberSkillId = memberSkillId;
        this.userId = userId;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.skillName = skillName;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
