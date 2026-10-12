package com.mannschaft.app.organization.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 組織のアーカイブ解除・論理削除の取消（復元）イベント（F01.2.1 2-D・§4.5）。
 *
 * <p>{@code OrganizationService#unarchiveOrganization}・{@code OrganizationService#restoreOrganization} が同じトランザクションで
 * 発行し、team ドメインの {@code TeamOrgLifecycleListener} が AFTER_COMMIT で受けて、組織の最後の閉鎖
 * （{@code organizations.last_closed_at}）より前に作られた PENDING を片付ける（閉鎖の片付けがまだ済んでいなくても、
 * 解除・復元で閉鎖前の申請・招待を復元しない）。組織はドメインをまたぐのでイベント方式とする。
 * リスナーの取りこぼしは定期バッチ {@code TeamOrgLifecycleCleanupBatch} が拾い直し、片付けまでの間の承認・承諾は
 * 閉鎖前の PENDING として拒む。</p>
 */
@Getter
public class OrganizationReopenedEvent extends BaseEvent {

    /** アーカイブ解除・復元された組織ID */
    private final Long organizationId;

    public OrganizationReopenedEvent(Long organizationId) {
        super();
        this.organizationId = organizationId;
    }
}
