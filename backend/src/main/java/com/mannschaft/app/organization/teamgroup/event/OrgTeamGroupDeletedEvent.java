package com.mannschaft.app.organization.teamgroup.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * チームグループ削除イベント（F01.2.1 §7.3）。
 *
 * <p>グループの論理削除と同じトランザクションで発行し、コミット後に team ドメインのリスナー
 * {@code TeamOrgGroupUnassignListener} が、削除されたグループを指す加盟行の {@code group_id} を
 * NULL（未分類）へ戻す。ドメイン間は ID のみで受け渡す（クロスドメイン FK なし）。</p>
 */
@Getter
public class OrgTeamGroupDeletedEvent extends BaseEvent {

    /** 組織 ID */
    private final Long organizationId;

    /** 削除されたチームグループ ID */
    private final UUID groupId;

    /** 削除操作者のユーザー ID */
    private final Long userId;

    public OrgTeamGroupDeletedEvent(Long organizationId, UUID groupId, Long userId) {
        super();
        this.organizationId = organizationId;
        this.groupId = groupId;
        this.userId = userId;
    }
}
