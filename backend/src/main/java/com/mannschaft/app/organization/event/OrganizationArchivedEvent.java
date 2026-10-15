package com.mannschaft.app.organization.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 組織アーカイブイベント（F01.2.1 §4.5・§6.9）。
 *
 * <p>{@code OrganizationService#archiveOrganization} がアーカイブのトランザクション内で発行し、
 * team ドメインの {@code TeamOrgLifecycleListener} が AFTER_COMMIT で受けて、当該組織の PENDING（申請・招待）を
 * 削除する。組織はドメインをまたぐのでイベント方式とする。</p>
 *
 * <p>試練（2-D）の骨格。出陣で発行側（{@code archiveOrganization}）と受け側を実装する。</p>
 */
@Getter
public class OrganizationArchivedEvent extends BaseEvent {

    /** アーカイブ操作者のユーザーID。システム経路（役職継承の自動アーカイブなど）では null。 */
    private final Long userId;

    /** アーカイブされた組織ID */
    private final Long organizationId;

    public OrganizationArchivedEvent(Long userId, Long organizationId) {
        super();
        this.userId = userId;
        this.organizationId = organizationId;
    }
}
