package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * チームメンバー操作イベント（汎用）。招待・参加・除名・ロール変更の監査ログをトリガーする。
 *
 * <p>サブタイプは {@link SubType} enum で区別する。</p>
 */
@Getter
public class TeamMemberAuditEvent extends BaseEvent {

    /** 操作の種類 */
    public enum SubType {
        INVITED,
        JOINED,
        ROLE_CHANGED,
        REMOVED,
        BLOCKED,
        UNBLOCKED
    }

    /** 操作者ユーザーID */
    private final Long userId;

    /** 操作対象ユーザーID（招待対象・参加者・除名対象など） */
    private final Long targetUserId;

    /** 対象チームID */
    private final Long teamId;

    /** 操作の種類 */
    private final SubType subType;

    public TeamMemberAuditEvent(Long userId, Long targetUserId, Long teamId, SubType subType) {
        super();
        this.userId = userId;
        this.targetUserId = targetUserId;
        this.teamId = teamId;
        this.subType = subType;
    }
}
