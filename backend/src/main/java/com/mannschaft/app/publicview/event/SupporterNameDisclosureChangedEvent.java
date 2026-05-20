package com.mannschaft.app.publicview.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import lombok.Getter;

/**
 * F19.1 Phase 2: supporter_name_disclosure 切替イベント。
 *
 * <p>ADMIN が {@code teams.supporter_name_disclosure} または
 * {@code organizations.supporter_name_disclosure} を変更した際に発行される。</p>
 *
 * <p><strong>リスナーは現時点で未実装。</strong>将来 Phase 5 以降で
 * 既存スナップショットの再計算・通知送信などが必要になった際に追加する。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6</p>
 */
@Getter
public class SupporterNameDisclosureChangedEvent extends BaseEvent {

    /** 変更対象チーム ID。組織の変更の場合は {@code null}。 */
    private final Long teamId;

    /** 変更対象組織 ID。チームの変更の場合は {@code null}。 */
    private final Long organizationId;

    /** 変更前の表示モード。 */
    private final NameDisclosureMode oldMode;

    /** 変更後の表示モード。 */
    private final NameDisclosureMode newMode;

    /** 変更を実施した操作者のユーザー ID。 */
    private final Long operatorUserId;

    public SupporterNameDisclosureChangedEvent(
            Long teamId,
            Long organizationId,
            NameDisclosureMode oldMode,
            NameDisclosureMode newMode,
            Long operatorUserId) {
        super();
        this.teamId = teamId;
        this.organizationId = organizationId;
        this.oldMode = oldMode;
        this.newMode = newMode;
        this.operatorUserId = operatorUserId;
    }
}
