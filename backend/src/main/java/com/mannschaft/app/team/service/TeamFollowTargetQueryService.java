package com.mannschaft.app.team.service;

import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 公開有効なフォロー先概要の読取境界。試練先行のため型のみ定義する。 */
@Service
@Transactional(readOnly = true)
public class TeamFollowTargetQueryService {
    public Map<Long, TeamFollowTargetSummary> findDisclosableSummaries(Collection<Long> teamIds) {
        throw new UnsupportedOperationException("CMP-261021-0020 試練先行の未実装");
    }
}
