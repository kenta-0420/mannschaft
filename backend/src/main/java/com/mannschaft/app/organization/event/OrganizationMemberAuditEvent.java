package com.mannschaft.app.organization.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 組織メンバー操作イベント（汎用）。参加・除名・ロール変更の監査ログをトリガーする。
 *
 * <p>サブタイプは {@link SubType} enum で区別する。</p>
 */
@Getter
public class OrganizationMemberAuditEvent extends BaseEvent {

    /** 操作の種類 */
    public enum SubType {
        JOINED,
        ROLE_CHANGED,
        REMOVED,
        BLOCKED,
        UNBLOCKED
    }

    /** 操作者ユーザーID */
    private final Long userId;

    /** 操作対象ユーザーID */
    private final Long targetUserId;

    /** 対象組織ID */
    private final Long organizationId;

    /** 操作の種類 */
    private final SubType subType;

    public OrganizationMemberAuditEvent(Long userId, Long targetUserId, Long organizationId, SubType subType) {
        super();
        this.userId = userId;
        this.targetUserId = targetUserId;
        this.organizationId = organizationId;
        this.subType = subType;
    }
}
