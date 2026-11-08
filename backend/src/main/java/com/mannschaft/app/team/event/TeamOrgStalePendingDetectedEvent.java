package com.mannschaft.app.team.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 承認・承諾が「相手（組織・チーム）の最後の閉鎖より前に作られた PENDING」を見つけたことを知らせるイベント
 * （F01.2.1 2-D・§4.5）。
 *
 * <p>その PENDING は閉鎖の片付けの前にアーカイブ解除・復元されたために残った無効な行であり、承認・承諾は行が無いのと
 * 同じ 404 で拒む（ACTIVE にしない）。拒否はトランザクションを巻き戻すので、片付けはロールバックの後に
 * {@code TeamOrgLifecycleListener} が自分のトランザクションで行う（行を消して相手へ取消を予約する）。</p>
 */
@Getter
public class TeamOrgStalePendingDetectedEvent extends BaseEvent {

    /** 加盟行のチームID */
    private final Long teamId;

    /** 加盟行の組織ID */
    private final Long organizationId;

    public TeamOrgStalePendingDetectedEvent(Long teamId, Long organizationId) {
        super();
        this.teamId = teamId;
        this.organizationId = organizationId;
    }
}
