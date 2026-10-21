package com.mannschaft.app.social.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** フォロー中の読取境界。試練先行のため型のみ定義する。 */
@Service
@Transactional(readOnly = true)
public class TeamFriendPendingFollowQueryService {
    public Page<PendingTeamFollowRow> findPendingFollows(Long teamId, Pageable pageable) {
        throw new UnsupportedOperationException("CMP-261021-0020 試練先行の未実装");
    }
}
