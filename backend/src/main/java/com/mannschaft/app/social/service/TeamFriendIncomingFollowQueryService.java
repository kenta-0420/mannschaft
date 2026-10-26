package com.mannschaft.app.social.service;

import com.mannschaft.app.social.repository.FollowRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** socialの値のみを読む受信申請一覧の読取境界。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamFriendIncomingFollowQueryService {
    private final FollowRepository followRepository;

    public Page<IncomingTeamFollowRow> findIncomingFollows(Long teamId, Pageable pageable) {
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            return new PageImpl<>(List.of(), pageable, followRepository.countIncomingTeamFollows(teamId));
        }
        return followRepository.findIncomingTeamFollows(teamId, pageable);
    }
}
