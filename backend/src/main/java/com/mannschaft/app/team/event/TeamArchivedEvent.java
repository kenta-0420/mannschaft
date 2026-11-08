package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * チームのアーカイブイベント（F01.2.1 2-D・§4.5）。
 *
 * <p>他ドメインのトランザクション（後継者不在の自動アーカイブ {@code RoleSuccessionService} など）からチームを
 * アーカイブしたときに発行し、team ドメインがコミット後に<b>自分のトランザクションで</b>加盟（PENDING）を片付ける
 * （{@code TeamOrgLifecycleListener}）。他ドメインのトランザクションに加盟の書き込みを持ち込まないため
 * （CLAUDE.md 原則5・D-3T）。チーム自身のアーカイブ API は同じトランザクションで片付けるので、このイベントを発行しない。
 * リスナーの取りこぼしは定期バッチ {@code TeamOrgLifecycleCleanupBatch} が状態から拾い直す。</p>
 */
@Getter
public class TeamArchivedEvent extends BaseEvent {

    /** アーカイブされたチームID */
    private final Long teamId;

    public TeamArchivedEvent(Long teamId) {
        super();
        this.teamId = teamId;
    }
}
